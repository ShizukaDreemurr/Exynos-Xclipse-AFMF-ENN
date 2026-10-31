"""Mạng flow nhỏ (kiểu IFNet-lite) theo hợp đồng của FluidFrames.

Input : [1,6,H,W]  prev RGB + cur RGB, 0..1, H,W bội số 32
Output: [1,5,H/2,W/2] = F_t->0 (x,y), F_t->1 (x,y) bằng pixel ở độ phân giải H,W; mask (sigmoid)

CHỦ Ý chỉ dùng: Conv2d, PReLU/ReLU, cộng, tanh, sigmoid, nhân hằng số.
KHÔNG có grid_sample / PixelShuffle / Resize — warp nằm trong shader Vulkan (xem docs/NPU_LIMITS.md).

Cảnh báo: file này chỉ định nghĩa kiến trúc. Trọng số khởi tạo ngẫu nhiên cho tới khi bạn huấn luyện
(ví dụ distill từ RIFE/IFRNet trên dữ liệu chụp game) và nạp checkpoint qua --ckpt.
"""
import torch
import torch.nn as nn

MAX_FLOW_PX = 24.0  # biên độ flow tối đa (pixel proc-res); phải khớp vùng nhìn thực tế của mạng


class ConvBlock(nn.Module):
    def __init__(self, cin, cout, stride=1):
        super().__init__()
        self.conv = nn.Conv2d(cin, cout, 3, stride, 1)
        self.act = nn.ReLU()  # ReLU thay PReLU: ổn định hơn khi lượng tử hóa INT8

    def forward(self, x):
        return self.act(self.conv(x))


class FlowNet(nn.Module):
    def __init__(self, width=32):
        super().__init__()
        self.stem = ConvBlock(6, width, stride=2)       # H,W -> H/2,W/2
        self.b1 = ConvBlock(width, width)
        self.b2 = ConvBlock(width, width)
        self.b3 = ConvBlock(width, width)
        self.b4 = ConvBlock(width, width)
        self.head = nn.Conv2d(width, 5, 3, 1, 1)

    def forward(self, x):
        h = self.stem(x)
        h = h + self.b2(self.b1(h))
        h = h + self.b4(self.b3(h))
        o = self.head(h)
        flow = torch.tanh(o[:, :4]) * MAX_FLOW_PX
        mask = torch.sigmoid(o[:, 4:5])
        return torch.cat([flow, mask], dim=1)


if __name__ == "__main__":
    net = FlowNet().eval()
    y = net(torch.rand(1, 6, 576, 1280))
    print(y.shape)  # [1,5,288,640]
