# 개발·배포

- [구조](#구조)
- [실행과 빌드](#실행과-빌드)
- [Windows 설치 파일](#windows-설치-파일)
- [새 버전 배포](#새-버전-배포)
- [테스트](#테스트)
- [참고](#참고)

## 구조

- `backend/` — Spring Boot 4.1.2 + Apache PDFBox 3.0.8 (Java 25 toolchain, Gradle wrapper 포함)
  - `com.pdfedit.text` — 텍스트 런 추출(`TextExtraction`), 덮개 영역 계산(`VerticalBounds`), 글꼴 매칭(`FontMatcher`), 설치된 글꼴 색인(`SystemFonts`)
  - `com.pdfedit.text.RunPainter` — 수정한 줄을 원본 글리프 그대로 다시 그림(부분 서식의 굵게·색·크기·밑줄 포함)
  - `com.pdfedit.ocr` — 스캔한 쪽의 글자 인식: PaddleOCR PP-OCRv5 모델(`src/main/resources/ocr`, 한국어)을 ONNX Runtime
    1.31로 Java에서 직접 돌림(`OcrEngine`, Python 불필요), 쪽 단위 인식(`PageOcr`), 내보낼 때 보이지 않는 글자층(`OcrTextLayer`)
  - `com.pdfedit.service.TextEditService` — 텍스트 수정 기록·미리보기·PDF에 굽기
  - `com.pdfedit.service.ImageDrawing` / `controller.ImageController` — 페이지에 넣는 그림(올리기·검사·PDF에 그리기)
  - `com.pdfedit.service.PdfExportService` — 페이지 조립(순서·회전) + 페이지별 텍스트 수정 반영
- `frontend/` — React + TypeScript + Vite (`TextEditor` 컴포넌트가 텍스트 편집 화면)
- `e2e/` — 실제 화면을 브라우저로 조작하는 자동 테스트 ([테스트](#테스트))

## 실행과 빌드

### 개발 (핫 리로드)

```
.\dev.ps1
```

백엔드(`:19000`)와 Vite 개발 서버(`:5173`)를 각각 새 창으로 띄웁니다. 브라우저에서는 `http://localhost:5173`을 여세요
(`/api`는 백엔드로 프록시됩니다). 백엔드는 개발 중 브라우저가 자동으로 열리지 않게 `PDFEDIT_NO_BROWSER=true`로 실행됩니다.

### 단일 jar

```
cd backend
.\gradlew.bat bootJar
java -jar build\libs\pdfedit-v2-backend-0.1.0.jar
```

`bootJar`가 `npm ci` + `npm run build`를 실행해 프런트엔드를 jar에 포함시킵니다 (Node 필요). 실행하면 비어 있는 포트(19000부터
탐색)를 골라 기본 브라우저를 엽니다. 이미 빌드된 `frontend/dist`를 쓰려면 `-PprebuiltFrontend`.

## Windows 설치 파일

```
.\installer\build-installer.ps1
```

`installer\dist\WINTECH_PDF_Setup_<버전>.exe`가 만들어집니다 (JDK 25 + [WiX Toolset](https://wixtoolset.org) 필요). 설치 파일 안에
Java 런타임이 들어 있어 사용자 PC에 Java가 없어도 됩니다. 설치하면 시작 메뉴·바탕화면 바로가기가 생기고, 실행하면 브라우저가 열리며
작업 표시줄 트레이 아이콘(열기/종료)이 뜹니다. 이미 실행 중이면 바로가기를 다시 눌러도 새로 띄우지 않고 실행 중인 화면을 엽니다.
로그는 `%USERPROFILE%\WINTECH_PDF\app.log`. 아이콘은 `installer\WINTECH_PDF.ico`(기존 WINTECH_PDF와 동일).

- **배포는 Setup.exe로만 합니다** (포터블 폴더/zip은 만들지 않음). 개발 중 설치 없이 확인하려면 `-AppImageOnly` —
  `installer\build\app-image\WINTECH DocEditor\WINTECH DocEditor.exe`를 바로 실행할 수 있습니다.
- **업그레이드 코드** `{86BDF49F-B988-375C-BC21-F945E6338154}`는 바꾸면 안 됩니다(`build-installer.ps1`의 `$upgradeCode`). 이
  코드가 같아야 새 설치 파일이 이전 설치를 같은 프로그램으로 알아보고 교체합니다.
- 설치된 앱의 작업 보관 폴더는 `%USERPROFILE%\WINTECH_PDF\data`, 로그는 `%USERPROFILE%\WINTECH_PDF\app.log` 입니다. 앱을 지우거나 새
  버전으로 덮어 설치해도 이 폴더는 그대로 남습니다.
- **버전**: 화면·파일 이름·트레이에는 `app.version`(예: 0.035)이 쓰이지만, Windows 설치 파일 버전은 숫자 3자리여야 하고 낮은 버전으로는
  덮어 설치할 수 없으므로 `1.0.<버전×1000>`(0.035 → 1.0.35, 0.036 → 1.0.36)으로 넣습니다. 이전 v1 설치본(1.0.1) 위에 그대로
  업그레이드 설치됩니다.
- 코드 서명이 없어서 처음 실행할 때 Windows SmartScreen이 "알 수 없는 게시자" 경고를 띄울 수 있습니다 (더 알아보기 → 실행).
- 설치 폴더의 `app\THIRD-PARTY-NOTICES.txt`에 오픈소스 고지가 들어갑니다([licenses](../licenses/README.md)).

## 새 버전 배포

- **GitHub에서 빌드(권장):** `master`에 올라올 때마다 GitHub Actions(`.github/workflows/build-installer.yml`, Windows 서버)가 설치
  파일을 만들어 그 실행의 Artifacts에 14일 보관합니다(내려받아 시험 설치). 릴리스하려면 GitHub의 Actions → `설치 파일 빌드` →
  `Run workflow`에서 `릴리스로 올리기`를 켜고 실행합니다: 설치 파일을 만들어 `publish-release.ps1`로 `v<버전>` 릴리스를 올립니다
  (PC에 JDK·WiX·gh가 없어도 됨). 서버에서는 WiX 5(.NET 도구)를 씁니다. 문서(`*.md`)나 화면 테스트(`e2e/`)만 바뀐 커밋은 자동 빌드를
  건너뛰고, 자동 빌드와 릴리스는 서로 다른 대기열이라 릴리스가 새 커밋에 취소되지 않습니다.
- **PC에서 배포:** `app.version`을 올리고, 바뀐 점을 `installer\release-notes\<버전>.md`에 **한글로** 써서 함께 커밋·푸시한 뒤
  `.\installer\build-installer.ps1`로 빌드하고 `.\installer\publish-release.ps1`을 실행하면 태그 `v<버전>`, 제목
  `WINTECH 문서편집기 v<버전>`으로 릴리스를 만들어 설치 파일과 `.sha256`을 올립니다. 릴리스 설명은 그 파일에서 가져오며(앞부분이 앱의
  업데이트 안내에 그대로 보임), 파일이 없거나 한글이 없으면 올리지 않습니다. `gh auth login`으로 GitHub CLI에 로그인해 두어야 합니다.

## 테스트

세 겹으로 되어 있고, 위로 갈수록 실제 사용에 가깝습니다.

```
cd backend;  .\gradlew.bat test            # 백엔드: 글자 추출·지우기·그리기·API (페이지를 그려서 픽셀로 확인하는 테스트 포함)
cd frontend; npx vitest run                # 화면 부품: 상자·서식 계산·겹침 경고 등 (jsdom)
cd frontend; npm run lint                  # 코드 검사 (public/hwp-studio의 내려받은 편집기 파일은 검사하지 않음)
cd backend;  .\gradlew.bat bootJar         # 아래 화면 테스트가 쓸 jar를 먼저 만듭니다
cd e2e;      npm ci; npm test              # 실제 화면: 앱을 새로 띄워 Edge로 조작
```

- **화면 테스트(`e2e/`, Playwright)** 는 jar를 빈 데이터 폴더·별도 포트(19090, `E2E_PORT`로 변경)로 띄우고, 이 PC에 설치된 **Microsoft Edge**
  (`E2E_BROWSER=chrome`이면 Chrome)를 조작합니다. 브라우저를 따로 내려받지 않습니다. PDF·그림은 테스트가 직접 만들어 쓰므로 준비할 파일이
  없습니다. 지금은 줄 수정→내보내기한 PDF의 글자 확인, 실행 취소, 부분 서식(굵게·밑줄·크기·색), 그림 넣기·크기 조절·삭제, 암호 PDF 열기(틀린 암호·취소 포함)→암호 다시 걸어 내보내기(새 암호를 묻는지), 페이지 복제(고친 글자째로), 쪽 맞춤, 스캔 PDF 글자 인식→내보낸 PDF에서 글자 찾기, 개인정보 찾아 가리기→내보낸 파일에서 글자가 사라졌는지, 가림 영역 직접 그리기·지우기, 두 문서 비교(바뀐 어절 표시·모양 차이), 한글 문서 열기→글자 입력→HWPX·HWP 저장(입력한 글자가 저장 파일에 들어 있는지, 바깥
  서버로 나간 요청이 없는지)을 확인합니다.
  실패하면 `e2e/test-results`에 화면 캡처와 추적 파일(`npx playwright show-trace`)이 남습니다.
- 화면 글자 크기·모양 같은 "보기에 맞는가"는 자동으로 알 수 없어서, 그런 변경은 직접 화면으로 확인합니다.

## 참고

- 외부 도구(LibreOffice, Tesseract, qpdf)는 쓰지 않습니다. 글자 인식은 앱에 든 모델과 ONNX Runtime으로, 암호 걸기는 PDFBox만으로
  합니다. 문서 변환을 붙일 때는 별도 설치가 필요합니다.
- ONNX Runtime jar(약 49MB)에는 Windows·Linux·macOS용 실행 파일이 모두 들어 있습니다. 설치 파일은 `gradlew bootJarWindows`로 만든
  Windows용만 남긴 jar(`*-windows.jar`, 약 42MB 작음)를 쓰고, 화면 테스트는 보통 `bootJar`를 씁니다.
- 이전 `pdf_edit_v1`에서 가져온 것: 텍스트 추출·글꼴 매칭·덮개 영역·내보내기 로직(PDFBox 3으로 이식, 같은 입력에서 v1과 **픽셀
  단위로 동일한 결과**임을 확인), 편집 UI(Thymeleaf → React). 버린 것: v1이 v2를 자식 프로세스로 띄우던 구조와 홈 링크, 두 앱의 포트 분리.
- 이 저장소는 v1(`pdf_edit_v1`: PDF 내용 편집기, Spring Boot 3 + PDFBox 2 + Thymeleaf)과 v2(페이지 편집기)를 합친 것이고, v4는
  v3(0.035)에 글자 모양 편집·이동·텍스트 추가·그림 넣기·부분 서식을 더한 별도 프로그램입니다.
- 설치된 프로그램·바로가기·Program Files 폴더 이름은 0.079부터 **WINTECH DocEditor**입니다(그 전에는 `WINTECH_PDF`). 같은 업그레이드
  코드를 써서, 업데이트하면 옛 WINTECH_PDF를 지우고 그 자리에 설치됩니다. 데이터 폴더(`%USERPROFILE%\WINTECH_PDF`)와 설치 파일 이름
  (`WINTECH_PDF_Setup_<버전>.exe`, 자동 업데이트가 이 이름으로 찾음)은 그대로입니다.
