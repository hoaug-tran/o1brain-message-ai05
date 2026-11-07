# Lá chắn AI (AI05)

Đội O(1).Brain, mã UNETi22. Thư mục bài nộp: `UNETi22_O(1).Brain_AI05`.

Dự án phân loại tin nhắn tiếng Anh thành Ham hoặc Spam. Web chạy Python; Android suy luận ngay trên thiết bị bằng trọng số SVM xuất sang JSON.

## Chạy web

Cần Python 3.12 và [uv](https://docs.astral.sh/uv/getting-started/installation/). Mở PowerShell tại thư mục đã giải nén:

```powershell
uv sync --locked
New-Item -ItemType Directory -Force .task
uv run alembic upgrade head
uv run uvicorn ai05.interfaces.http:app --host 127.0.0.1 --port 8000 --no-access-log
```

Trên Linux, thay lệnh tạo thư mục bằng `mkdir -p .task`. Mở http://127.0.0.1:8000/#check để nhập tin nhắn và phân loại. Trang so sánh chạy baseline NB và SVM trên cùng nội dung. Trọng số đã có sẵn; không cần tải dữ liệu hay huấn luyện lại để demo.

Sau khi cài và khởi tạo cơ sở dữ liệu lần đầu, có thể ngắt Internet rồi chạy `run_windows.ps1` hoặc `sh run_linux.sh`. Gói nộp đã có báo cáo, font và trọng số. Chỉ phần báo cáo cộng đồng cần mạng; tính năng này không bắt buộc khi demo.

Kiểm tra API bằng PowerShell:

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health/ready
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/api/v1/demo/compare -ContentType application/json -Body '{"text":"Hey, are we meeting at the library tomorrow?"}'
```

Web không gửi tin nhắn thật. Hội thoại minh họa chỉ nằm trong bộ nhớ trình duyệt. API không lưu nội dung nhập hoặc truy cập liên kết trong tin nhắn.

## Dữ liệu và đánh giá

Nguồn do BTC chỉ định: https://archive.ics.uci.edu/dataset/228/sms+spam+collection.

Dữ liệu gốc có **5.574 tin** (4.827 Ham, 747 Spam). SHA-256: `7d039a24a6083ed9ef0f806ebad56bbb976e3aeb8de05669173bfdc4996c239d`. Gói ZIP không phân phối lại dữ liệu. Để tải về khi tái huấn luyện:

```powershell
uv run python scripts/download_data.py
```

Script kiểm tra hash trước khi ghi file. Giấy phép và nguồn dữ liệu nằm trong `licenses/` và `data/manifests/sources.json`.

Theo baseline BTC, dữ liệu được loại nhóm có nhãn mâu thuẫn và tin trùng sau khi `strip/lower`, trước khi chia tập. Dữ liệu hiện tại không có nhóm nhãn mâu thuẫn; loại 415 bản trùng, còn **5.159 tin**. Chia phân tầng 80/20, seed 2026:

- Train: 4.127 tin (3.613 Ham, 514 Spam).
- Validation: 1.032 tin (904 Ham, 128 Spam).

ID của từng tập được cố định tại `data/manifests/english_split.json`.

Tỷ lệ 86,60% Ham và 13,40% Spam trong slide 3 tính trên dữ liệu gốc. Tỷ lệ sau loại trùng khác vì hai lớp có số bản trùng khác nhau. Thống kê độ dài, topwords và word cloud chỉ dùng tập train. Việc chuẩn hóa từ để vẽ EDA không phải bước tiền xử lý chung của các mô hình.

Mã baseline BTC giữ tại `vendor/btc/AI05`. Adapter đọc TSV với `QUOTE_NONE` để dấu nháy được xử lý như nội dung tin nhắn. Cấu hình gốc không đổi: TF-IDF từ (n-gram 1-2), `sublinear_tf=True`, MultinomialNB `alpha=0,5`. Chạy lại bằng:

```powershell
uv run python scripts/reproduce_baseline.py --out .task/btc-replay
```

Có **30 cấu hình** được thử trên cùng 5 fold của tập train:

- Multinomial NB và Complement NB: `alpha = 0,1 / 0,5 / 1`.
- Logistic Regression, SVM từ và SVM ký tự: `C = 0,1 / 1 / 4 / 10` kết hợp `class_weight = None / balanced`.

Vocabulary và IDF chỉ học trên phần train của từng fold. Chọn cấu hình có F1 Spam trên dự đoán ngoài fold (OOF) cao nhất; nếu bằng nhau, ưu tiên Ham FPR thấp hơn, sau đó theo thứ tự cố định. Các họ mô hình dùng cùng tiêu chí chọn và giữ ngưỡng mặc định.

`alpha` là tham số làm trơn của NB; `C` điều chỉnh mức phạt của mô hình tuyến tính. Hai tham số khác bản chất, không cần cùng giá trị. `class_weight=balanced` tăng trọng số lớp ít mẫu khi học, không nhân bản tin nhắn hay thay đổi phân bố validation. Kết quả tối ưu phản ánh toàn bộ pipeline; chưa tách riêng tác động của TF-IDF ký tự.

| Mô hình | F1 Spam OOF | F1 Spam validation | Precision Spam validation | Ham FPR validation |
| --- | ---: | ---: | ---: | ---: |
| Baseline NB | 79,34% | 86,22% | 100,00% | 0,00% |
| NB alpha=0,1 | 92,75% | 95,51% | 100,00% | 0,00% |
| Complement NB | 93,22% | 92,61% | 92,25% | 1,11% |
| Logistic Regression | 93,44% | 93,39% | 93,02% | 1,00% |
| SVM từ | 93,72% | 94,40% | 96,72% | 0,44% |
| SVM ký tự | 95,98% | 95,12% | 99,15% | 0,11% |

**Mô hình chọn theo OOF:** SVM, TF-IDF ký tự 3-5, `C=1`, `class_weight=balanced`, seed 2026, ngưỡng 0. Web dùng `results/report/selected_model.joblib`; Android dùng bản trọng số xuất từ cùng mô hình. Hash được đối chiếu qua registry và bản xuất Android.

NB `alpha=0,1` có F1 Spam validation **95,51%**, cao hơn SVM ký tự (**95,12%**). Nhóm không dùng kết quả validation để chọn lại mô hình. Quá trình tối ưu cũng không bảo đảm mọi cấu hình đều tăng điểm validation.

Spam là lớp dương. Các công thức: Precision = TP/(TP+FP), Recall = TP/(TP+FN), F1 = 2TP/(2TP+FP+FN), Ham FPR = FP/(FP+TN). Theo thứ tự TN/FP/FN/TP, baseline là `904/0/31/97`; SVM được chọn là `903/1/11/117`. Cần đọc đúng lớp và tập dữ liệu khi đối chiếu bảng, hình.

### Validation và kiểm thử

Theo mục 2.5 của đề BTC, không có bộ test bí mật để kiểm thử độc lập. AI05 yêu cầu đánh giá baseline và cải tiến trên cùng validation. Nhóm dùng OOF để chọn cấu hình, validation để báo kết quả.

Dự án **chưa có tập test dữ liệu độc lập**. Validation đã được xem trong quá trình phát triển và còn 19 tin gần trùng train ở ngưỡng cosine 0,9. Vì vậy, không gọi đây là test mù hoặc chia lại dữ liệu đã dùng rồi coi là test độc lập. Để đánh giá khả năng tổng quát hóa, cần khóa pipeline và kiểm tra trên một tập mới chưa tham gia chọn cấu hình. Điểm OOF sau tìm kiếm cũng không phải ước lượng test không thiên lệch.

Các bài kiểm thử trong `tests/` và Android kiểm tra mã, API, dữ liệu, hash và độ khớp suy luận. Đây là kiểm thử phần mềm, không phải tập test đánh giá hiệu năng mô hình.

## Notebook Colab và tạo lại hình

Mở `AI05_Colab.ipynb` trên Google Colab, chọn runtime CPU và chạy **Runtime > Run all**. Notebook tự cài đúng phiên bản thư viện, tải UCI và kiểm tra hash. Không cần Drive, API key, GPU hay import mã từ project. Cần Internet để cài thư viện và tải dữ liệu.

Notebook huấn luyện lại 30 cấu hình, đối chiếu dự đoán OOF và validation với kết quả lưu sẵn, kiểm tra thống kê từ rồi tạo hình. Histogram và hai word cloud dùng đúng vùng cắt trong PowerPoint; biểu đồ tỷ lệ, ma trận nhầm lẫn, so sánh trước/sau tối ưu và topwords dựa trên cùng số đếm.

`AI05_ket_qua.zip` chứa PNG/SVG, các chỉ số, dự đoán và trọng số đã tái tạo. Notebook bàn giao không lưu sẵn hình hoặc đầu ra lớn trong ô để mở nhanh.

Chạy trong project:

```powershell
uv run python -m ml.eda --output .task/report-replay
uv run python scripts/build_notebook.py
```

Nếu thư mục đầu ra đã tồn tại, chọn tên khác. Xem báo cáo tại `results/report/index.html` hoặc http://127.0.0.1:8000/research/. Các tệp plan, selection, evidence, keywords và snapshot mã nguồn dùng để đối chiếu kết quả. Model trong snapshot lịch sử không phải model đang phục vụ.

## Android

APK UCI 1.0.0 ở `android/O1Brain_Message_UCI_1.0.0.apk` được ký debug để cài thử. Yêu cầu build: JDK 17 trở lên, Android SDK 35, build-tools 36.0.0, Gradle 9.2.1. Đặt `ANDROID_HOME` trỏ tới Android SDK rồi chạy:

```powershell
gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Mô hình chạy trên thiết bị nhưng **chỉ được huấn luyện và đánh giá với SMS tiếng Anh**. Giao diện tiếng Việt không đồng nghĩa với khả năng phân loại SMS tiếng Việt. Các quy tắc cảnh báo bổ sung và lựa chọn của người dùng tách khỏi nhãn, điểm SVM. Chỉ số trong báo cáo đo mô hình phân loại, không đo toàn bộ bộ lọc của app. SVM dùng ngưỡng 0, chưa có ngưỡng Spam cao hơn được hiệu chỉnh riêng.

Khi làm ứng dụng SMS mặc định, app có thể quản lý kho SMS hệ thống. Chế độ hỗ trợ chỉ theo dõi, cảnh báo; không chặn SMS tới ứng dụng mặc định khác. Chưa hỗ trợ RCS. Báo cáo cộng đồng chỉ gửi số và loại báo cáo sau khi người dùng xác nhận, không gửi nội dung SMS.

Bản UCI chưa được xác nhận gửi/nhận qua SIM và MMS trên mọi thiết bị, nhà mạng.

## Kiểm tra và đóng gói

```powershell
uv run ruff check .
uv run ruff format --check .
uv run mypy src/ai05 ml
uv run pytest -q
node --test tests/web.test.mjs
uv run python scripts/verify_artifacts.py
uv run python scripts/offline_smoke.py
```

Node.js 22 trở lên chỉ cần cho kiểm thử web. `offline_smoke.py` chạy HTTP cục bộ và chặn kết nối ra ngoài trong tiến trình phục vụ, không phải phép thử ngắt mạng vật lý.

Gói có `slides.pdf` gồm 13 trang hiển thị và `slides.pptx` với 4 phụ lục ẩn. PDF đáp ứng giới hạn 15 trang nội dung. Phần demo nằm ngoài 10 phút thuyết trình, theo lịch 10/5/5 của BTC.

```powershell
uv run python scripts/package_offline.py --slides slides.pdf --presentation slides.pptx --apk android/O1Brain_Message_UCI_1.0.0.apk --output "dist/UNETi22_O(1).Brain_AI05"
```

Gói gồm bảng SHA-256, mã nguồn, kiểm thử, notebook, trọng số, báo cáo và tài liệu tham khảo. Không chứa môi trường Python, cache build, DB, khóa, log, dữ liệu tin nhắn thô.

Thư mục nộp: `UNETi22_O(1).Brain_AI05`. File ZIP: `O(1).Brain_Vòng chung kết.zip`.

## Cấu trúc dự án và vai trò từng tệp

Danh mục tệp của nhánh `main`, tính từ thư mục gốc.

```text
UNETi22_O(1).Brain_AI05/
  .dockerignore : Chọn các tệp được đưa vào ngữ cảnh build Docker.
  .env.example : Mẫu biến môi trường để cấu hình ứng dụng.
  .gitattributes : Giữ nguyên byte khi Git checkout để bảo toàn các hash; cấu hình của kho Git.
  .gitignore : Loại môi trường cài đặt, dữ liệu thô, khóa, DB và cache khỏi Git.
  .python-version : Chỉ định Python 3.12 cho uv.
  AI05_Colab.ipynb : Notebook tự tải UCI, chạy lại CV, đánh giá và tạo hình trong báo cáo.
  alembic.ini : Cấu hình Alembic và đường dẫn các bước cập nhật cơ sở dữ liệu.
  android/
    app/
      build.gradle.kts : Cấu hình build, phiên bản ứng dụng, thư viện, kiểm thử và hash tài nguyên mô hình.
      src/
        androidTest/
          assets/
            parity.json : Các mẫu tổng quát để đối chiếu điểm, nhãn Python trên Android thật.
            ten-sms-parity.json : 10 tin giả lập kèm nhãn và điểm Python để kiểm tra suy luận, giao diện.
            user-diagnostic-parity.json : Các mẫu phân tích lỗi và điểm, nhãn Python tương ứng của mô hình hiện tại.
          java/
            vn/
              uneti/
                ai05/
                  CommunityFlowTest.kt : Kiểm tra HTTPS thật cho đăng ký, gửi, tra cứu và rút báo cáo bằng dữ liệu giả lập.
                  ConversationActionsTest.kt : Kiểm tra bộ lọc chưa đọc, quy tắc người gửi và thao tác sửa nội dung bằng bàn phím.
                  DeviceContractTest.kt : Kiểm tra tài nguyên UCI trong APK, nhãn, điểm Kotlin và suy luận không lưu nội dung mẫu.
                  IntentNavigationTest.kt : Kiểm tra mở chat từ thông báo và không lặp yêu cầu điều hướng đã xử lý.
                  LayoutCaptureTest.kt : Kiểm tra khả năng thao tác với chữ lớn, bàn phím và chụp ảnh giao diện khi bật kiểm thử.
                  LeaderCaptureTest.kt : Chụp các màn ứng dụng bằng hộp thư giả lập khi chủ động bật chế độ kiểm thử.
                  LoadingSkeletonTest.kt : Kiểm tra trạng thái chờ được thay bằng trạng thái hộp thư thực tế sau khi tải.
                  MessengerDeviceTest.kt : Kiểm tra lưu mã hóa, nháp, đọc tin, lưu trữ và lựa chọn tin rác trên thiết bị.
                  MessengerUiTest.kt : Kiểm tra vuốt lưu trữ, xác nhận xóa, điều hướng, tìm kiếm và mở nội dung từ bên ngoài.
                  NavigationRegressionTest.kt : Kiểm tra bộ đếm tin rác chưa đọc, quay lại đúng mục và giữ vị trí cuộn.
                  ProductFlowTest.kt : Kiểm tra luồng tin đến, phân tích, trạng thái gửi, migration và quy tắc cá nhân.
                  QuickCheckTest.kt : Kiểm tra nhập liệu, xóa kết quả cũ và hiển thị nhãn, điểm khớp suy luận thực tế.
        main/
          AndroidManifest.xml : Khai báo quyền, activity, service, receiver và FileProvider của ứng dụng.
          assets/
            english_char_svm.json : Từ vựng, IDF, trọng số và bias của đúng SVM UCI đang được triển khai.
            model_evidence.json : Chỉ số validation và nguồn gốc của đúng mô hình được đóng trong APK.
          evidence.sha256 : Hash của tệp bằng chứng mô hình Android.
          java/
            com/
              google/
                android/
                  mms/
                    pdu_alt/
                      BoundedPduParser.java : Đọc PDU đa phương tiện với giới hạn kích thước và số phần; giữ thông báo bản quyền.
            vn/
              uneti/
                ai05/
                  AppRuntime.kt : Nạp mô hình, kiểm tra hash và gọi suy luận trên thiết bị.
                  BoundedInput.kt : Đọc luồng dữ liệu với giới hạn dung lượng cho mô hình và tệp đính kèm.
                  ChatScreen.kt : Hiển thị danh sách hội thoại, đoạn chat, nội dung và cảnh báo của từng tin.
                  CheckSamples.kt : Cung cấp các tin tiếng Anh minh họa cho màn kiểm tra.
                  CommunityClient.kt : Đăng ký, ký yêu cầu, gửi, tra cứu, thử lại và rút báo cáo cộng đồng.
                  CommunityProtocol.kt : Tính chữ ký yêu cầu và bằng chứng đăng ký theo giao thức máy chủ.
                  CommunitySettings.kt : Giao diện mở, gửi và quản lý báo cáo cộng đồng trong phần cài đặt.
                  CommunitySyncService.kt : Lên lịch đồng bộ và gửi lại các báo cáo cộng đồng đang chờ.
                  ConversationActions.kt : Menu thao tác hội thoại và hộp thoại xác nhận báo cáo tin nhắn.
                  LoadingSkeleton.kt : Hiển thị trạng thái chờ tải danh sách hội thoại.
                  LocTheme.kt : Cấu hình màu sáng, tối, kiểu chữ và chuyển động của giao diện Compose.
                  MainActivity.kt : Khởi tạo ứng dụng, điều hướng và xử lý yêu cầu mở chat hoặc soạn tin từ bên ngoài.
                  MessageStatus.kt : Ánh xạ trạng thái gửi, nhận và lỗi của tin thường, tin đa phương tiện.
                  MessageStore.kt : Lưu dữ liệu cục bộ, mã hóa, đọc kho tin hệ thống và quản lý nháp, nhãn, bộ lọc.
                  MessengerController.kt : Điều phối tải, phân tích, gửi, đánh dấu, chuyển mục và cập nhật trạng thái hội thoại.
                  MessengerScreens.kt : Các màn hộp thư, bộ lọc, chọn chế độ, cài đặt và thanh điều hướng.
                  MmsOperations.kt : Nhận, tải, gửi tin đa phương tiện và mở tệp đính kèm qua cơ chế Android.
                  PortableClassifier.kt : Tái hiện TF-IDF ký tự và điểm quyết định Linear SVM từ trọng số JSON.
                  ProductDetector.kt : Tách nhãn SVM khỏi cảnh báo bổ sung, kiểm tra phạm vi ngôn ngữ và phiên bản đánh giá.
                  QuickCheckScreen.kt : Nhập tin tiếng Anh, phân tích trên thiết bị và xem nhãn, điểm, bằng chứng mô hình.
                  SmsOperations.kt : Gửi, ghi nhận và cập nhật trạng thái tin nhắn trong kho hệ thống.
                  SmsReceiver.kt : Nhận thông báo tin đến từ Android và chuyển dữ liệu hợp lệ cho controller.
                  UiPolicy.kt : Kiểm tra nội dung, người nhận và ánh xạ trạng thái, lỗi sang thông báo giao diện.
          model.sha256 : Hash của tệp trọng số JSON để kiểm tra khi nạp mô hình.
          res/
            drawable/
              ic_loc_mark.xml : Biểu tượng thương hiệu dạng vector.
              ic_loc_notification.xml : Biểu tượng nhỏ dùng trong thông báo Android.
              loc_launch.xml : Nền và biểu tượng khi mở ứng dụng.
            font/
              manrope.ttf : Font Manrope dùng khi tạo word cloud.
            mipmap-anydpi-v26/
              ic_launcher.xml : Biểu tượng ứng dụng thích ứng trên Android 8 trở lên.
            mipmap-anydpi-v33/
              ic_launcher.xml : Biểu tượng ứng dụng cho Android 13 trở lên, gồm lớp đơn sắc.
            values-night-v31/
              brand.xml : Cấu hình màn khởi động tối trên Android 12 trở lên.
            values-night/
              brand.xml : Màu và giao diện khởi động trong chế độ tối.
            values-v31/
              brand.xml : Cấu hình màn khởi động theo API Android 12 trở lên.
            values/
              brand.xml : Màu thương hiệu và giao diện khởi động sáng, tối mặc định.
              messenger.xml : Chuỗi hiển thị của hội thoại, gửi nhận, bộ lọc và thao tác tin nhắn.
              strings.xml : Chuỗi tên ứng dụng, kiểm tra nội dung, kết quả và bằng chứng mô hình.
            xml/
              mms_paths.xml : Giới hạn đường dẫn chia sẻ tệp đính kèm qua FileProvider.
        test/
          java/
            com/
              google/
                android/
                  mms/
                    pdu_alt/
                      BoundedPduParserTest.java : Kiểm tra bộ đọc PDU từ chối độ dài, số phần hoặc cấu trúc không hợp lệ.
            vn/
              uneti/
                ai05/
                  BoundedInputTest.kt : Kiểm tra giới hạn đọc mô hình và từ chối dữ liệu vượt giới hạn đã yêu cầu.
                  CommunityProtocolTest.kt : Đối chiếu chữ ký và bằng chứng đăng ký Kotlin với giao thức Python.
                  MessengerPolicyTest.kt : Kiểm tra lọc hội thoại, số chưa đọc và chuyển trạng thái gửi, nhận, lỗi.
                  PortableClassifierTest.kt : Đối chiếu điểm, nhãn với Python và kiểm tra đầu vào, nhãn không được hỗ trợ.
                  ProductDetectorTest.kt : Kiểm tra cảnh báo bổ sung, tin dễ nhầm và việc tính lại đánh giá đã cũ.
                  UiPolicyTest.kt : Kiểm tra đầu vào, người nhận và việc không gán trạng thái chưa biết thành Ham.
          resources/
            parity.json : Mẫu đối chiếu Python dành cho kiểm thử classifier trên JVM.
    build.gradle.kts : Khai báo phiên bản plugin Android và Kotlin cho toàn bộ project Android.
    gradle.properties : Thiết lập bộ nhớ Gradle, AndroidX và quy ước Kotlin.
    O1Brain_Message_UCI_1.0.0.apk : APK UCI 1.0.0 ký debug để cài thử.
    settings.gradle.kts : Khai báo module app và các kho tải plugin, thư viện Android.
  artifacts/
    english_baseline/
      en_7d039a24_6fe3229b_2026_7585946abe1e/
        metadata.json : Phiên bản, nguồn, môi trường, ngưỡng và các hash của baseline NB.
        model.joblib : Trọng số baseline NB của lần chạy đối chiếu ban đầu.
        train_manifest.json : ID train, validation và hash split gắn với trọng số baseline NB.
        validation_metrics.json : Chỉ số, ma trận nhầm lẫn và thông tin đánh giá đã lưu của baseline NB.
    english_char_svm/
      en_7d039a24_6fe3229b_2026_7585946abe1e/
        metadata.json : Phiên bản, nguồn, môi trường, ngưỡng và các hash của SVM ký tự.
        model.joblib : Trọng số SVM ký tự của lần chạy đối chiếu ban đầu.
        train_manifest.json : ID train, validation và hash split gắn với trọng số SVM ký tự.
        validation_metrics.json : Chỉ số, ma trận nhầm lẫn và thông tin đánh giá đã lưu của SVM ký tự.
      uci_cv_balanced_20261107/
        metadata.json : Phiên bản, nguồn, môi trường, ngưỡng và các hash của SVM ký tự.
        model.joblib : Trọng số SVM ký tự C=1, balanced dùng cho web và bản xuất Android.
    english_logreg/
      en_7d039a24_6fe3229b_2026_7585946abe1e/
        metadata.json : Phiên bản, nguồn, môi trường, ngưỡng và các hash của Logistic Regression.
        model.joblib : Trọng số Logistic Regression của lần chạy đối chiếu ban đầu.
        train_manifest.json : ID train, validation và hash split gắn với trọng số Logistic Regression.
        validation_metrics.json : Chỉ số, ma trận nhầm lẫn và thông tin đánh giá đã lưu của Logistic Regression.
    registry.json : Chỉ định baseline, mô hình đang phục vụ và các đường dẫn, hash cần xác minh.
  checksums.json : SHA-256 của các tệp trong gói nộp.
  data/
    manifests/
      competition.json : Lưu mã đội, phạm vi đề, yêu cầu hồ sơ và lịch trình do BTC cung cấp.
      data_quality.json : Thống kê nhãn, bản trùng, độ dài và các cặp gần trùng giữa train, validation.
      english_split.json : ID cố định của train, validation cùng seed, số mẫu và hash nguồn.
      sources.json : URL, hash và nguồn gốc dữ liệu, baseline, tài liệu BTC.
  deploy/
    compose.yml : Cấu hình hai dịch vụ web demo và báo cáo cộng đồng, cổng và volume dữ liệu.
    Dockerfile : Build image Python chứa mã ứng dụng, trọng số và tài nguyên báo cáo.
    public.yml : Cấu hình bổ sung Cloudflare Tunnel, đọc thông tin tunnel từ thư mục ngoài project.
  licenses/
    Manrope-OFL.txt : Giấy phép SIL Open Font License của font Manrope.
    UCI-readme.txt : Thông báo và điều kiện sử dụng gốc đi kèm corpus UCI.
  migrations/
    env.py : Kết nối cấu hình ứng dụng với tiến trình migration của Alembic.
    versions/
      0001_metadata_only.py : Tạo hoặc gỡ các bảng sự kiện, phản hồi và thông tin chống phát lại.
  ml/
    __init__.py : Đánh dấu gói Python ml.
    benchmark.py : Khai báo mô hình, lưới tham số và chọn cấu hình bằng dự đoán OOF trên train.
    common.py : Tính hash, ghi JSON, chụp thông tin môi trường và hash mã nguồn.
    datasets.py : Đọc UCI, loại trùng, chia tập theo BTC và kiểm tra gần trùng giữa hai tập.
    eda.py : Thống kê từ ngữ, chạy so sánh mô hình và xuất báo cáo cùng các hình.
    metrics.py : Tính ma trận nhầm lẫn, F1, Precision, Recall lớp Spam và FPR trên Ham.
    slides.py : Cắt hình báo cáo và tạo các hình tương ứng với nội dung PowerPoint.
  pyproject.toml : Khai báo gói Python, thư viện, công cụ kiểm tra và cấu hình pytest.
  README.md : Hướng dẫn chạy, đánh giá, đóng gói và tra cứu từng tệp.
  requirements.txt : Thư viện chạy ứng dụng với phiên bản và hash đã khóa.
  results/
    demo_evidence/
      dependency-audit.json : Kết quả kiểm tra thư viện và thông tin xử lý đã được lưu ở lần kiểm tra.
      offline_smoke.json : Kết quả kiểm tra HTTP suy luận với kết nối ra ngoài bị chặn.
    experiments/
      en_7d039a24_6fe3229b_2026_7585946abe1e/
        btc/
          btc_train_parser_corrected.py : Bản mã thực thi baseline sau khi thêm QUOTE_NONE; mã vendor vẫn giữ nguyên.
          demo_messages.csv : Tám tin validation do baseline xuất làm ví dụ; tệp này không đóng trong gói nộp.
          metrics.json : Chỉ số và ma trận nhầm lẫn xuất trực tiếp từ lần chạy baseline BTC.
          model.joblib : Trọng số xuất trực tiếp từ baseline BTC với parser đã sửa.
          parser_correction.json : Hash mã gốc, mã thực thi và nội dung thay đổi parser.
        comparison.json : Kết quả đối chiếu baseline và các mô hình của lần chạy ban đầu.
        config.json : Cấu hình mô hình, dữ liệu, CV, tiêu chí chọn và các ngưỡng của lần chạy.
        english_baseline.json : Chỉ số, ma trận nhầm lẫn và hash trọng số baseline NB của lần chạy này.
        english_baseline_predictions.json : ID, nhãn thật và nhãn dự đoán của baseline NB trên validation.
        english_char_svm.json : Chỉ số, ma trận nhầm lẫn và hash trọng số SVM ký tự của lần chạy này.
        english_char_svm_predictions.json : ID, nhãn thật và nhãn dự đoán của SVM ký tự trên validation.
        english_logreg.json : Chỉ số, ma trận nhầm lẫn và hash trọng số Logistic Regression của lần chạy này.
        english_logreg_predictions.json : ID, nhãn thật và nhãn dự đoán của Logistic Regression trên validation.
        environment.json : Phiên bản Python, hệ điều hành và thư viện tại thời điểm chạy thí nghiệm.
        model_selection.json : Mô hình được chọn, tiêu chí lựa chọn và các hash liên quan.
        train_cv.json : Kết quả CV, ID train, số fold và seed của lần chạy ban đầu.
        training_source.zip : Snapshot mã và cấu hình dùng để tái lập lần chạy ban đầu.
      uci_cv_balanced_20261107/
        comparison.json : So sánh baseline và SVM balanced được registry hiện tại tham chiếu.
        english_baseline.json : Chỉ số, ma trận nhầm lẫn và hash trọng số baseline NB của lần chạy này.
        english_baseline_predictions.json : ID, nhãn thật và nhãn dự đoán của baseline NB trên validation.
        english_char_svm.json : Chỉ số, ma trận nhầm lẫn và hash trọng số SVM ký tự của lần chạy này.
        english_char_svm_predictions.json : ID, nhãn thật và nhãn dự đoán của SVM ký tự trên validation.
    report/
      evidence.json : Chỉ số, nhãn thật, dự đoán, điểm và dữ liệu đường cong của sáu cấu hình.
      figures.json : Tên, tiêu đề và hash của từng hình PNG, SVG trong báo cáo.
      figures/
        01_classes.png : Ảnh phân bố Ham và Spam trước, sau làm sạch.
        01_classes.svg : Tệp SVG: phân bố Ham và Spam trước, sau làm sạch.
        02_quality.png : Ảnh số tin qua các bước làm sạch và chia tập.
        02_quality.svg : Tệp SVG: số tin qua các bước làm sạch và chia tập.
        03_lengths.png : Ảnh phân bố độ dài ký tự và số từ theo lớp trên train.
        03_lengths.svg : Tệp SVG: phân bố độ dài ký tự và số từ theo lớp trên train.
        05_signals.png : Ảnh tỷ lệ tin có chữ số, URL, tiền tệ, dấu chấm than và từ viết hoa.
        05_signals.svg : Tệp SVG: tỷ lệ tin có chữ số, URL, tiền tệ, dấu chấm than và từ viết hoa.
        06_ham_wordcloud.png : Ảnh word cloud các từ phổ biến trong Ham.
        06_ham_wordcloud.svg : Tệp SVG: word cloud các từ phổ biến trong Ham.
        06_spam_wordcloud.png : Ảnh word cloud các từ phổ biến trong Spam.
        06_spam_wordcloud.svg : Tệp SVG: word cloud các từ phổ biến trong Spam.
        06_tokens.png : Ảnh tần suất từ phổ biến của từng lớp trên train.
        06_tokens.svg : Tệp SVG: tần suất từ phổ biến của từng lớp trên train.
        07_phrases.png : Ảnh tần suất cụm từ liền kề của từng lớp trên train.
        07_phrases.svg : Tệp SVG: tần suất cụm từ liền kề của từng lớp trên train.
        08_models.png : Ảnh so sánh F1 Spam, Precision Spam và Ham FPR sau chọn tham số.
        08_models.svg : Tệp SVG: so sánh F1 Spam, Precision Spam và Ham FPR sau chọn tham số.
        09_confusion.png : Ảnh ma trận nhầm lẫn của sáu cấu hình trên cùng validation.
        09_confusion.svg : Tệp SVG: ma trận nhầm lẫn của sáu cấu hình trên cùng validation.
        10_precision_recall.png : Ảnh đường cong Precision, Recall và AP của các cấu hình.
        10_precision_recall.svg : Tệp SVG: đường cong Precision, Recall và AP của các cấu hình.
        11_tuning.png : Ảnh F1 Spam, Precision Spam và Ham FPR trước, sau chọn tham số.
        11_tuning.svg : Tệp SVG: F1 Spam, Precision Spam và Ham FPR trước, sau chọn tham số.
      index.html : Trang báo cáo phân tích dữ liệu và so sánh sáu cấu hình.
      keywords.json : Số đếm từ, cụm từ, tỷ lệ xuất hiện và quy tắc xử lý từ ngữ trên train.
      metrics.csv : Bảng chỉ số và số đếm TN, FP, FN, TP của sáu cấu hình trên validation.
      plan.json : Khóa dữ liệu, ID chia tập, lưới tham số, CV, môi trường và hash snapshot mã.
      selected_model.joblib : SVM ký tự C=1, balanced được chọn từ OOF và fit lại trên toàn bộ train.
      selection.json : Kết quả của 30 cấu hình, dự đoán OOF và tham số được chọn cho từng họ.
      signals.json : Mẫu biểu thức và số tin chứa chữ số, URL, tiền tệ, dấu chấm than, từ viết hoa.
      slides/
        slide03_classes.png : Tỷ lệ Ham và Spam trong dữ liệu nguồn, tương ứng slide 3.
        slide04_lengths.png : Histogram ký tự và số từ đã cắt đúng vùng dùng ở slide 4.
        slide05_ham.png : Word cloud Ham đã cắt đúng vùng dùng ở slide 5.
        slide05_spam.png : Word cloud Spam đã cắt đúng vùng dùng ở slide 5.
        slide10_confusion.png : Hai ma trận baseline, SVM được chọn và thanh màu chung cho nội dung slide 10.
        slide16_tuning.png : F1 validation trước và sau chọn tham số, tương ứng phụ lục 16.
        slide17_words.png : Tám từ phổ biến của mỗi lớp và số lượt xuất hiện, tương ứng phụ lục 17.
      style.css : Định dạng trang HTML của báo cáo.
      training_source.zip : Snapshot mã Python, cấu hình project và khóa thư viện của lần tạo báo cáo.
  run_linux.sh : Khởi tạo DB rồi chạy web offline trên Linux sau bước cài thư viện.
  run_windows.ps1 : Khởi tạo DB rồi chạy web offline trên Windows sau bước cài thư viện.
  scripts/
    build_notebook.py : Tạo notebook Colab từ mã phân tích, font và bằng chứng đã lưu trong project.
    community_admin.py : Lệnh quản trị cục bộ để thu hồi quyền của một thiết bị đã đăng ký.
    create_demo_sms.py : Tạo thông tin xác thực cục bộ và gửi hoặc xóa sự kiện tin nhắn giả lập có chữ ký.
    download_data.py : Tải corpus UCI, kiểm tra kích thước và hash trước khi ghi dữ liệu.
    export_android_evidence.py : Xuất chỉ số validation của đúng trọng số Android và kiểm tra nguồn gốc bằng hash.
    export_android_model.py : Xuất SVM sang JSON cho Android và tạo mẫu đối chiếu nhãn, điểm Python với Kotlin.
    offline_smoke.py : Chạy HTTP cục bộ với kết nối ra ngoài bị chặn để kiểm tra suy luận offline.
    package_offline.py : Đóng gói mã, notebook, slides, APK, trọng số và bằng chứng; lập bảng SHA-256.
    reproduce_baseline.py : Chạy baseline BTC qua adapter chỉ sửa cách đọc dấu nháy trong TSV.
    smoke_api.py : Kiểm tra HTTP thật cho web, mô hình, báo cáo và trường hợp đầu vào lỗi.
    verify_artifacts.py : Kiểm tra hash trọng số và đối chiếu dự đoán trên các ID validation đã lưu.
    verify_environment.py : Kiểm tra phiên bản Python và sự hiện diện của cấu hình, dữ liệu, registry.
  slides.pdf : Bản trình chiếu nộp BTC gồm 13 trang hiển thị.
  slides.pptx : Bản PowerPoint chỉnh sửa được, gồm 13 slide chính và 4 phụ lục ẩn.
  src/
    ai05/
      __init__.py : Đánh dấu gói Python ai05.
      application.py : Điều phối kiểm tra đầu vào, suy luận, cảnh báo và đo thời gian dự đoán.
      config.py : Đọc cấu hình môi trường và kiểm tra các chế độ lưu, nhận dữ liệu được hỗ trợ.
      domain.py : Định nghĩa kết quả, lỗi đầu vào và quy tắc cảnh báo từ nhãn phân loại.
      infrastructure/
        community.py : Lưu đăng ký thiết bị, báo cáo, tra cứu, thu hồi và giới hạn yêu cầu cộng đồng.
        events.py : Xác thực yêu cầu bằng chữ ký, chống phát lại và lưu sự kiện dạng metadata.
        models.py : Nạp registry, kiểm tra hash và phiên bản thư viện, rồi suy luận bằng trọng số đã khóa.
      interfaces/
        community.py : API đăng ký thiết bị, gửi, tra cứu và rút báo cáo cộng đồng.
        http.py : API phân loại, so sánh, sự kiện và phục vụ web, báo cáo; giới hạn yêu cầu HTTP.
        schemas.py : Khai báo cấu trúc và ràng buộc JSON cho đầu vào, kết quả và sự kiện.
      web/
        app.js : Xử lý tương tác web, hội thoại minh họa, gọi API và hiển thị kết quả.
        index.html : Bố cục các màn hình hội thoại, kiểm tra và cài đặt trên web.
        style.css : Kiểu chữ, màu, bố cục đáp ứng và trạng thái giao diện web.
  tests/
    fixtures/
      user_sms_15.json : 15 tin giả lập có nhãn kỳ vọng để kiểm tra và phân tích lỗi.
    integration/
      test_api.py : Kiểm tra API phân loại, so sánh, đầu vào lỗi, OpenAPI và tài nguyên báo cáo.
      test_artifacts.py : Kiểm tra trọng số lỗi, đường dẫn vượt thư mục và nạp lại mô hình trong tiến trình mới.
      test_community.py : Kiểm tra báo cáo, thu hồi, xác thực, chống phát lại, hạn mức và bảo vệ dữ liệu cộng đồng.
      test_events.py : Kiểm tra sự kiện có chữ ký, tính lặp an toàn, hết hạn, phân quyền và rollback khi lỗi.
      test_security.py : Kiểm tra giới hạn dữ liệu, Unicode, migration và việc không ghi hoặc trả lại nội dung khi lỗi.
    scientific/
      test_datasets.py : Kiểm tra parser, loại trùng, nhóm mâu thuẫn, chia tập và tránh học từ vựng từ validation.
      test_download.py : Kiểm tra hash dữ liệu tải về và bảo vệ tệp đã có khi dữ liệu mới không hợp lệ.
      test_keywords.py : Kiểm tra đếm từ, cụm từ, viết tắt, stopword và tính lặp lại của word cloud.
      test_metrics.py : Đối chiếu công thức chỉ số bằng số đếm tính tay và kiểm tra đầu vào không hợp lệ.
      test_report.py : Tính lại chỉ số, kiểm tra dữ liệu vẽ hình và bảo vệ báo cáo đã lưu khỏi ghi đè.
      test_selection.py : Đối chiếu OOF và kiểm tra TF-IDF chỉ được fit trong phần train của từng fold.
      test_submission.py : Kiểm tra model triển khai, notebook, hình và số liệu biểu đồ PowerPoint khớp bằng chứng.
    test_android_evidence.py : Kiểm tra bằng chứng Android đúng model, đúng hash và từ chối dữ liệu đã bị sửa.
    test_environment.py : Kiểm tra báo cáo tệp còn thiếu mà không tự tạo đầu vào thay thế.
    test_packaging.py : Kiểm tra bộ đóng gói từ chối slides PDF thiếu hoặc không hợp lệ.
    test_score_diagnostics.py : Kiểm tra nhãn luôn khớp điểm và ngưỡng; từ chối kết quả mô hình mâu thuẫn.
    web.test.mjs : Kiểm tra giao diện web, mẫu minh họa, xóa kết quả cũ và lựa chọn giao diện sáng, tối.
  uv.lock : Khóa phiên bản và nguồn tải thư viện để cài lại môi trường bằng uv.
  vendor/
    btc/
      AI05/
        demo.py : Mã demo nguyên bản đi kèm baseline BTC.
        train.py : Mã huấn luyện baseline nguyên bản do BTC cung cấp.
```

## Nguồn và phần thực hiện

Dữ liệu: bộ dữ liệu tin nhắn UCI theo chỉ định của BTC. Mã baseline BTC: `vendor/btc/AI05`. Phiên bản thư viện và hash nằm trong `uv.lock`, `requirements.txt`; font Manrope và mã MMS có thông báo bản quyền đi kèm. Không sử dụng mô hình tin nhắn pretrained bên ngoài.

Nhóm thực hiện kiểm tra dữ liệu, so sánh và chọn cấu hình, báo cáo, API, web và tích hợp Android. Quy tắc loại trùng và thuật toán baseline do BTC cung cấp. OpenAI Codex được dùng để hỗ trợ lập trình, kiểm thử, biên tập; không tham gia suy luận tin nhắn. Phần dùng lại và công cụ hỗ trợ được công khai theo mục 2.1 của đề.

**Giới hạn:** Điểm SVM là điểm quyết định có dấu, không phải xác suất đã hiệu chỉnh. Điểm NB cũng chưa hiệu chỉnh. Ham không có nghĩa tin nhắn an toàn; Spam không đồng nghĩa với lừa đảo. Đây là nguyên mẫu nghiên cứu, chưa đủ bằng chứng để triển khai trong nhà trường.
