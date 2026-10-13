# PhotoViewer - 포토블로그 안드로이드 클라이언트

강의자료 ch05(모바일 앱으로 사용하는 기본 포토 블로그)의 PhotoViewer를 Java로 구현.
PhotoBlogServer(Django)의 REST API로 게시물을 받아오고(동기화), 새 사진 글을 올린다(업로드).

- Android Studio Quail 4, compileSdk 37 / minSdk 24, Java 11
- 테스트 에뮬레이터 : Medium Phone API 37

## 실행

1. PhotoBlogServer 실행 (`python manage.py runserver`)
2. Android Studio에서 `PhotoViewer` 폴더 열기 → Gradle Sync → Run
3. 에뮬레이터에서 내 컴퓨터 서버 주소는 `http://10.0.2.2:8000` (기본값)

## 화면 / 기능

| 기능 | 설명 |
|---|---|
| 동기화 | `GET /api_root/Post/` 로 게시물 목록(JSON Array)을 받고 각 게시물의 이미지를 내려받아 RecyclerView에 표시 |
| 새로운 이미지 게시 | 제목, 내용 입력 + 갤러리에서 사진 선택 → `POST /api_root/Post/` (multipart) |
| 로그인 | 아이디/비밀번호로 `/api-token-auth/` 에서 토큰 발급, 서버 주소별로 저장 (코드에 토큰을 직접 넣지 않음) |
| 서버 주소 변경 | 메뉴 → 로컬(10.0.2.2:8000) / PythonAnywhere / 직접 입력 |
| 상세 보기 / 삭제 | 게시물을 누르면 크게 보기, 로그인 상태면 삭제 가능 (서버에서 작성자만 허용) |
| 검색 | 상단 돋보기 → 제목/내용으로 목록 필터 |
| 당겨서 새로고침 | 목록을 아래로 당기면 다시 동기화 |

## 구조

```
app/src/main/java/com/example/photoviewer/
├── MainActivity.java    onClickDownload / onClickUpload, CloadImage(동기화), PutPost(업로드)
├── ImageAdapter.java    RecyclerView 어댑터 (사진 + 제목/날짜/내용, 검색 필터)
├── PhotoBlogApi.java    HttpURLConnection으로 REST API 호출 (GET / POST multipart / DELETE / 토큰)
├── Post.java            게시물 JSON -> 객체
├── ImageUtils.java      목록용 이미지 축소 디코딩, 업로드용 JPEG 변환(긴 변 1920px)
└── BackgroundTask.java  AsyncTask 대신 사용 (ExecutorService + Handler)
```

### 강의자료 코드에서 바꾼 점

- `AsyncTask` 는 API 30부터 deprecated라서 같은 모양(`doInBackground` / `onPostExecute`)의 `BackgroundTask` 를 만들어 사용
- 토큰을 코드에 하드코딩하지 않고 로그인으로 발급 (`Authorization: Token <토큰>` 헤더는 수업 자료와 동일)
- 사진 선택은 Photo Picker 사용 → 저장공간 권한(READ/WRITE/MANAGE_EXTERNAL_STORAGE) 불필요
- 큰 사진은 화면 폭에 맞게 줄여서 디코딩 (메모리 부족 방지)
- 업로드할 때 `Content-Length` 를 정해서 보냄 (`setFixedLengthStreamingMode`) → 소켓 서버에서도 요청 본문을 끝까지 받을 수 있음

## 테스트

```bash
./gradlew test    # Post JSON 변환, multipart 본문, 이미지 축소 비율 단위 테스트
```

## Socket Server로 요청 확인 (강의자료 ch05 p24)

1. Django 서버를 끄고 소켓 서버(`socket_server.py`, 127.0.0.1:8000)를 실행
2. 앱에서 동기화(GET) 또는 게시(POST) → 소켓 서버의 `request/` 폴더에 요청 메시지가 `.bin` 으로 저장됨
3. VSCode로 `.bin` 파일을 열어 요청 라인, 헤더, 본문 확인
