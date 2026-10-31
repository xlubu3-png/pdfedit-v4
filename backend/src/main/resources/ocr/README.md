# OCR models

PaddleOCR PP-OCRv5 models in ONNX form, as published by RapidOCR (v3.10.0). Both are Apache-2.0 licensed
(PaddleOCR: https://github.com/PaddlePaddle/PaddleOCR, RapidOCR: https://github.com/RapidAI/RapidOCR).

| File | Model | Source | SHA-256 |
|---|---|---|---|
| `det.onnx` | ch_PP-OCRv5_det_mobile (finds text lines; works for Korean) | https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx | `4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae` |
| `rec-korean.onnx` | korean_PP-OCRv5_rec_mobile (reads a line; its character list is in the model's `character` metadata) | https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/rec/korean_PP-OCRv5_rec_mobile.onnx | `cd6e2ea50f6943ca7271eb8c56a877a5a90720b7047fe9c41a2e541a25773c9b` |

Chosen after a test on 2026-10-31: on simulated 300 dpi scans the Korean v5 model got 0.4-1.6 % of characters
wrong (the v4 Korean model 7-11 %), at about 2 s a page on a desktop CPU. PP-OCRv6 has no Korean model yet.
