"""Huấn luyện SR x2 (residual trên bilinear) từ video game. Chạy CPU.
Mạng: LR(3ch) -> conv3x3 x N (ReLU) -> 12ch -> pixel-shuffle x2, cộng với bilinear(LR).
Trên NPU chỉ chạy phần conv (ra 12 kênh), GPU làm pixel-shuffle + cộng bilinear."""
import sys, time, glob, numpy as np, cv2, torch, torch.nn as nn, torch.nn.functional as F
torch.set_num_threads(2); torch.manual_seed(0); np.random.seed(0)
VIDS = sys.argv[1].split(","); OUT = sys.argv[2]; MINUTES = float(sys.argv[3]); CH = int(sys.argv[4]); NL = int(sys.argv[5])
CROP = 192  # HR crop

def load_crops(path, n_frames, per, held):
    cap = cv2.VideoCapture(path); n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    idx = np.linspace(0, n - 1, n_frames).astype(int); tr, te = [], []
    for k, i in enumerate(idx):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(i)); ok, f = cap.read()
        if not ok: continue
        H, W = f.shape[:2]; is_te = k >= n_frames * (1 - held)
        for _ in range(1 if is_te else per):
            y = np.random.randint(0, H - CROP + 1) // 2 * 2; x = np.random.randint(0, W - CROP + 1) // 2 * 2
            (te if is_te else tr).append(f[y:y + CROP, x:x + CROP, ::-1].copy())
    return tr, te

tr, te = [], []
for v in VIDS:
    a, b = load_crops(v, 360, 6, 0.1); tr += a; te += b
tr = np.stack(tr); te = np.stack(te); print("train", tr.shape, "test", te.shape, flush=True)

def down(hr):  # hr: B,3,H,W float 0..1 -> area x2
    return F.avg_pool2d(hr, 2)

class Net(nn.Module):
    def __init__(s, ch, nl):
        super().__init__()
        L = [nn.Conv2d(3, ch, 3, padding=1), nn.ReLU()]
        for _ in range(nl - 2): L += [nn.Conv2d(ch, ch, 3, padding=1), nn.ReLU()]
        L += [nn.Conv2d(ch, 12, 3, padding=1)]
        s.body = nn.Sequential(*L)
    def res(s, lr): return s.body(lr)
    def forward(s, lr):
        return F.interpolate(lr, scale_factor=2, mode="bilinear", align_corners=False) + F.pixel_shuffle(s.res(lr), 2)

def psnr(a, b): return float(10 * torch.log10(1 / F.mse_loss(a.clamp(0, 1), b)))
def tens(x): return torch.from_numpy(x).permute(0, 3, 1, 2).float() / 255

net = Net(CH, NL); opt = torch.optim.Adam(net.parameters(), 2e-3)
Te = tens(te); Tel = down(Te)
with torch.no_grad():
    base = psnr(F.interpolate(Tel, scale_factor=2, mode="bilinear", align_corners=False), Te)
print(f"bilinear PSNR held-out: {base:.3f} dB", flush=True)
t0 = time.time(); step = 0; BS = 16; P = 64
while time.time() - t0 < MINUTES * 60:
    ii = np.random.randint(0, len(tr), BS)
    hr = tens(tr[ii]); oy, ox = np.random.randint(0, (CROP - 2 * P) // 2 + 1, 2) * 2
    hr = hr[:, :, oy:oy + 2 * P, ox:ox + 2 * P]
    if np.random.rand() < .5: hr = hr.flip(3)
    lr = down(hr); loss = F.l1_loss(net(lr), hr)
    opt.zero_grad(); loss.backward(); opt.step(); step += 1
    frac = (time.time() - t0) / (MINUTES * 60)
    for g in opt.param_groups: g["lr"] = 2e-3 * (0.5 * (1 + np.cos(np.pi * frac))) + 1e-5
    if step % 200 == 0:
        with torch.no_grad(): p = psnr(net(Tel[:64]), Te[:64])
        print(f"step {step} loss {loss.item():.4f} testPSNR(64) {p:.3f} t={time.time()-t0:.0f}s", flush=True)
with torch.no_grad():
    outs = torch.cat([net(Tel[i:i + 32]) for i in range(0, len(Tel), 32)])
print(f"FINAL float PSNR {psnr(outs, Te):.3f} dB vs bilinear {base:.3f} dB (+{psnr(outs, Te) - base:.3f})", flush=True)
torch.save({"sd": net.state_dict(), "ch": CH, "nl": NL}, OUT)
