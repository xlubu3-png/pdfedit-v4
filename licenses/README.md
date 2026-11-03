# 오픈소스 라이선스 의무와 관리

WINTECH 문서편집기는 **회사 안에서 쓰는 프로그램**입니다. 이 문서는 프로그램에 들어 있는 오픈소스의 라이선스가 요구하는 것과,
그것을 이 저장소에서 어떻게 지키는지 정리합니다. (일반적인 정리이며 법률 자문이 아닙니다. 회사 밖으로 팔거나 나눠 줄 계획이 생기면
그때 한 번 더 검토하세요.)

## 한눈에 보기

| 구성 요소 | 라이선스 | 요구하는 것 | 이 저장소에서 |
|---|---|---|---|
| rhwp / rhwp-studio (한글 편집기) | MIT | 저작권 표시와 라이선스 전문을 함께 둘 것 | `frontend/public/hwp-studio/LICENSE-rhwp.txt`, 고지 파일 |
| rhwp가 포함한 Rust·npm 구성 요소 (CanvasKit 등) | MIT·Apache-2.0·BSD-3-Clause 등 | 같음 (BSD는 저작권 표시) | `hwp-studio/THIRD_PARTY_LICENSES.md`, Skia 라이선스는 고지 파일 |
| React, React DOM, scheduler | MIT | 저작권 표시와 전문 | 고지 파일 (자동) |
| lucide-react (아이콘) | ISC | 저작권 표시와 전문 | 고지 파일 (자동) |
| Spring Boot, Spring, Tomcat, PDFBox, Jackson, Log4j API, Micrometer, commons-logging, SnakeYAML, JSpecify | Apache-2.0 | 라이선스 사본 제공, **NOTICE 파일 내용 전달**, 고친 파일에 변경 표시 | 고지 파일에 LICENSE·NOTICE 원문 (자동). 이 라이브러리들은 고쳐 쓰지 않음 |
| SLF4J, ONNX Runtime | MIT | 저작권 표시와 전문 (ONNX Runtime은 포함한 구성 요소 고지도) | 고지 파일 (자동 + `texts/onnxruntime-*`) |
| Logback | EPL-1.0 / LGPL-2.1 중 선택 | EPL-1.0을 골라 씀: 라이선스 사본 제공, 소스를 얻을 수 있는 곳 안내 | 고지 파일 (`texts/logback-LICENSE.txt`, `EPL-1.0.txt`). 소스: https://github.com/qos-ch/logback |
| Jakarta Annotations | EPL-2.0 / GPL-2.0+CE | 라이선스 사본 제공 | 고지 파일 (자동) |
| PaddleOCR PP-OCRv5 모델 (RapidOCR 변환본) | Apache-2.0 | 라이선스 사본, 출처 표시 | 고지 파일, `backend/src/main/resources/ocr/README.md` |
| 한글 편집기 글꼴 (나눔·Noto·Pretendard·D2Coding 등) | SIL OFL 1.1 | 라이선스와 저작권 표시를 함께 둘 것, **글꼴만 따로 팔지 말 것** | 고지 파일, `hwp-studio/fonts/FONTS.md` |
| Latin Modern Math | GUST Font License (LPPL 1.3c) | 라이선스 사본, 고치면 이름 바꾸기 권고 | 고지 파일 (`texts/LPPL-1.3c.txt`) |
| 카페24 써라운드·슈퍼매직, 행복고딕 | 각 배포처의 무료 글꼴 약관 | 배포처 안내로는 기업도 무료로 쓸 수 있고, 글꼴 파일 자체를 팔거나 고쳐 배포하면 안 됨 | 고치거나 따로 배포하지 않음 |
| Java 실행 환경 (OpenJDK, Eclipse Temurin) | GPL-2.0 + Classpath Exception | 라이선스 사본, 소스 안내 | 설치 폴더 `runtime\legal` (jlink가 자동으로 넣음). 고쳐 쓰지 않음 |

이 가운데 **소스 공개 의무가 있는 것(GPL·LGPL·EPL의 수정본)은 고쳐 쓰지 않으므로** 우리 프로그램의 소스를 공개할 의무는 없습니다.
지켜야 할 것은 결국 하나입니다: **프로그램과 함께 저작권 표시·NOTICE·라이선스 원문을 둘 것.** 회사 안에서만 쓰더라도 설치 파일을
여러 PC에 나눠 주므로 지키는 것이 안전합니다.

## 이 저장소에서 지키는 방법

1. **고지 파일을 빌드할 때 자동으로 만듭니다.** `backend/notices.gradle`의 `thirdPartyNotices` 작업이 실제로 들어가는 것 -
   서버의 Java 라이브러리(jar 안의 `META-INF/LICENSE*`·`NOTICE*`)와 화면의 npm 패키지(`package-lock.json`의 배포용 패키지와
   `node_modules`의 LICENSE) - 를 읽고, 이 폴더의 `texts/`(jar나 패키지에 원문이 없는 것)와 한글 편집기 파일을 더해
   `THIRD-PARTY-NOTICES.txt`를 만듭니다. 같은 원문은 한 번만 싣고 어디에 적용되는지 적습니다.
2. **빠짐이 없게 막습니다.** 라이선스를 알 수 없는 Java 라이브러리나 LICENSE 파일이 없는 npm 패키지가 새로 들어오면 빌드가 멈추고
   추가할 곳을 알려 줍니다(`javaWithoutFiles`, `npmWithoutFiles`).
3. **사용자가 볼 수 있습니다.** 고지 파일은 앱 안(`/THIRD-PARTY-NOTICES.txt`, 첫 화면의 `오픈소스 라이선스`, 편집 화면 도움말)과
   설치 폴더(`app\THIRD-PARTY-NOTICES.txt`)에 들어갑니다. 한글 편집기의 라이선스 파일도 도움말에서 열립니다.

## 라이브러리를 더하거나 바꿀 때

- Java·npm 라이브러리는 버전만 바뀌면 할 일이 없습니다(자동). 빌드가 "라이선스를 알 수 없습니다"로 멈추면, 그 라이브러리의 저장소에서
  LICENSE 원문을 받아 `texts/`에 넣고 `backend/notices.gradle`의 `javaWithoutFiles`(또는 `npmWithoutFiles`)에 적습니다.
- **GPL·AGPL 라이브러리는 넣지 마세요.** 프로그램 전체의 소스를 공개해야 할 수 있습니다. LGPL·EPL·MPL은 고쳐 쓰지 않으면 괜찮습니다.
- 한글 편집기(rhwp)를 새 버전으로 바꾸면(`tools/build-hwp-studio.ps1`) `LICENSE-rhwp.txt`·`THIRD_PARTY_LICENSES.md`·`fonts/FONTS.md`가
  함께 바뀝니다. 새 글꼴이 OFL이 아니면 위 표와 `notices.gradle`의 글꼴 목록을 고칩니다.
- 남의 소스 코드를 복사해 고쳐 넣었다면, 그 파일 머리에 원래 저작권 표시와 라이선스를 남기고 위 표에 더합니다.

## texts/ 의 원문

| 파일 | 출처 |
|---|---|
| `onnxruntime-LICENSE.txt`, `onnxruntime-ThirdPartyNotices.txt` | github.com/microsoft/onnxruntime v1.31.0 |
| `logback-LICENSE.txt` | github.com/qos-ch/logback v_1.5.38 |
| `EPL-1.0.txt`, `OFL-1.1.txt`, `LPPL-1.3c.txt` | SPDX license-list-data |
| `jspecify-LICENSE.txt` | github.com/jspecify/jspecify v1.0.0 |
| `snakeyaml-LICENSE.txt` | github.com/snakeyaml/snakeyaml |
| `skia-LICENSE.txt` | github.com/google/skia (CanvasKit) |
| `PaddleOCR-LICENSE.txt`, `RapidOCR-LICENSE.txt` | github.com/PaddlePaddle/PaddleOCR, github.com/RapidAI/RapidOCR |
