"""AOT biên dịch model SR x2 đã huấn luyện (INT8 vào/ra) cho Exynos 2600 (E9965). Ra out_sr_trained/c_sr_x2_1170x540/."""
import os, sys
from ai_edge_litert.aot import aot_compile as aot
from ai_edge_litert.aot.vendors.samsung import target as st
raw = sys.argv[1]; name = os.path.basename(raw).replace("_raw.tflite", "")
tgt = st.Target(st.SocModel.E9965)
for strict in (True, False):
    try:
        aot.aot_compile(raw, output_dir=os.path.join("out_sr_trained", "c_" + name), target=tgt, keep_going=not strict)
        print("SRRESULT", name, "AOT_OK" if strict else "AOT_PARTIAL"); break
    except Exception as e:
        print("SRRESULT", name, "AOT_STRICT_FAIL" if strict else "AOT_FAIL", str(e)[:300].replace("\n", " "))
