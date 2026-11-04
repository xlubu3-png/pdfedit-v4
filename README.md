# WINTECH 문서편집기 (pdfedit_v4)

회사 안에서 쓰는 문서 편집 프로그램입니다. 브라우저 화면에서 PDF의 **본문 글자를 직접 고치고**, 여러 PDF의 페이지를 합치고 정리하며,
한글 문서(HWP·HWPX)를 열어 고치고, 두 문서를 비교하고, 개인정보를 찾아 가립니다. 모든 처리는 이 PC 안에서 하며 문서가 밖으로 나가지
않습니다. 지금 버전은 첫 화면 맨 위에 보입니다.

## 주요 기능

| 분야 | 할 수 있는 것 | 자세히 |
|---|---|---|
| PDF 글자 편집 | 본문 줄을 클릭해 고치기, 글꼴·크기·굵게·색·밑줄(일부 글자만도), 줄 옮기기, 텍스트·그림(도장·서명) 넣기 | [텍스트 편집](docs/text-editing.md) |
| 페이지 정리 | 여러 PDF 합치기, 끌어서 순서 바꾸기, 회전·삭제·복제·빈 페이지·추출, Ctrl+Z로 되돌리기 | [기능 상세](docs/features.md#페이지-목록첫-화면) |
| 내보내기 | PDF로 내보내기, 암호 걸기, 이미지로 변환, 용량 줄이기, **사진(JPG·PNG)으로 내보내기**, 인쇄 | [내보내기·인쇄](docs/features.md#내보내기인쇄) |
| 개인정보 가리기 | 주민번호·전화번호 등을 찾아 가리고, 내보낼 때 실제로 지움(지워졌는지 확인까지) | [개인정보 가리기](docs/features.md#개인정보-가리기) |
| 글자 인식(OCR) | 스캔한 PDF의 글자를 읽어 검색·복사, 잘못 읽은 글자 고치기, 스캔 글자 고쳐 쓰기 | [글자 인식](docs/ocr.md) |
| 한글 문서 | HWP·HWPX 열기·편집·저장, 새 한글 문서 만들기 | [한글 문서](docs/features.md#한글-문서) |
| 표 복사·엑셀 저장 | PDF의 표를 엑셀에 붙이면 칸·합친 칸·테두리 그대로(Ctrl+C), 원본과 똑같이는 `엑셀로 내보내기`(.xlsx) | [표 모양으로 복사](docs/ocr.md) |
| 비교·찾기 | 두 PDF의 바뀐 글자 비교, 모든 쪽에서 찾아 바꾸기 | [문서 비교와 찾아 바꾸기](docs/features.md#문서-비교와-찾아-바꾸기) |
| 관리 | 작업 자동 보관, 접속 비밀번호, 자동 업데이트 | [작업 보관 등](docs/features.md#작업-보관접속-비밀번호자동-업데이트) |

## 설치와 사용

1. [Releases](../../releases)에서 `WINTECH_PDF_Setup_<버전>.exe`를 받아 설치합니다. Java가 함께 들어 있어 따로 설치할 것이 없습니다.
   (코드 서명이 없어 처음 실행할 때 SmartScreen 경고가 뜨면 `추가 정보 → 실행`.)
2. 바탕화면의 **WINTECH DocEditor**를 실행하면 브라우저에 화면이 열리고, 작업 표시줄에 트레이 아이콘(열기·종료)이 생깁니다.
3. PDF나 한글 파일을 화면에 끌어다 놓고 작업한 뒤, 위쪽의 `PDF로 내보내기`(Ctrl+S)로 내려받습니다.

- 작업은 `%USERPROFILE%\WINTECH_PDF\data`에 자동 보관되어(기본 30일) 앱을 다시 켜도 이어서 할 수 있습니다. 로그는 같은 폴더의 `app.log`.
- 새 버전이 나오면 첫 화면에 안내가 뜨고 `지금 업데이트`로 설치합니다.
- 단축키와 사용법은 편집 화면의 `도움말`에 있습니다.

## 개발과 배포

- 구조, 개발 실행(`.\dev.ps1`), 빌드, 설치 파일, 테스트: [개발·배포](docs/development.md)
- 새 버전 배포: `backend/src/main/resources/application.yml`의 `app.version`을 올리고 `installer/release-notes/<버전>.md`에 바뀐 점을
  한글로 쓴 뒤 master에 올리고, GitHub의 **Actions → 설치 파일 빌드 → Run workflow**에서 `릴리스로 올리기`를 켜고 실행합니다
  ([자세히](docs/development.md#새-버전-배포)).

## 오픈소스 라이선스

이 프로그램에는 오픈소스가 들어 있습니다: 한글 편집기 [rhwp](https://github.com/edwardkim/rhwp)(MIT), PDFBox·Spring Boot(Apache-2.0),
ONNX Runtime(MIT), React(MIT), PaddleOCR 모델(Apache-2.0), 글꼴(SIL OFL) 등. 목록과 라이선스 원문은 빌드할 때 자동으로 만들어지는
`THIRD-PARTY-NOTICES.txt`에 모아 프로그램 안(첫 화면의 `오픈소스 라이선스`, 도움말)과 설치 폴더(`app\THIRD-PARTY-NOTICES.txt`)에
함께 넣습니다. 지켜야 할 것과 관리 방법은 [licenses/README.md](licenses/README.md)에 있습니다.
