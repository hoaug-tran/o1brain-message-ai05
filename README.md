# O1Brain Message, demo mở rộng

Đây là bản demo mở rộng riêng, không phải bản nộp BTC. Bản nộp và Android UCI nằm ở project Olympic. Giao diện web giữ nguyên các tệp HTML, JavaScript và CSS của bản gốc. Web so sánh baseline Multinomial NB và Linear SVM ký tự đã tối ưu. Android trong project này dùng SVM song ngữ riêng, phân tích trên thiết bị. Web giữ model tiếng Anh hiện có.

## Chạy web

Cần Python 3.12 và uv:

```powershell
uv sync --locked
New-Item -ItemType Directory -Force .task
uv run alembic upgrade head
uv run uvicorn ai05.interfaces.http:app --host 127.0.0.1 --port 8000 --no-access-log
```

Trên Linux dùng `mkdir -p .task` thay lệnh tạo thư mục. Mở http://127.0.0.1:8000. Trang kiểm tra chạy NB và SVM trên cùng nội dung. Trọng số đã có sẵn. Không cần Internet khi suy luận và không lưu nội dung SMS nhập.

Tên hiển thị trong giao diện kế thừa bản gốc theo yêu cầu giữ nguyên UI. Nguồn train thực tế của bản này là ba bộ dữ liệu bên dưới; không được mô tả trọng số mới là model chỉ học UCI. API trả supported_scope=expanded_english_experiment và validation_status=expanded_grouped_development_holdout.

## Dữ liệu và mô hình

- UCI SMS Spam Collection theo nguồn BTC: https://archive.ics.uci.edu/dataset/228/sms+spam+collection.
- SMS Phishing Dataset, Mishra và Soni, Mendeley Data V1 (2022): https://data.mendeley.com/datasets/f45bkkt8pr/1. CC BY 4.0. Bản CSV nguồn được giữ theo hash trong data/external/mendeley/SOURCE.json.
- Indian Telecom SMS Spam Collection: https://github.com/junioralive/india-spam-sms-classification. MIT. Revision 09137cc677a7d8d24caef0817de41f55a6b3ed44, 2.267 dòng nguồn; hash và URL trong data/external/india/SOURCE.json.

Giữ 4.127 train UCI và không đưa 1.032 validation UCI vào học. Các nguồn ngoài phải có nhãn hợp lệ, nằm trong giới hạn đầu vào và dùng ký tự Latin. Chuẩn hóa để lọc trùng, loại nhóm nhãn mâu thuẫn, loại bản gần UCI ở ngưỡng cosine 0,9. Phần dữ liệu ngoài còn lại được chia theo nhóm gần trùng; giữ lại 567 tin đánh giá và đưa 2.264 tin vào học. Tổng train: 6.391 tin. Không thêm các mẫu nút Random hoặc ví dụ tự viết vào train.

NB giữ thuật toán baseline: TF-IDF từ 1–2, sublinear_tf=True, MultinomialNB alpha=0.5. SVM giữ cấu hình tối ưu đã chọn trước: TF-IDF ký tự 3–5, sublinear_tf=True, LinearSVC C=1, class_weight=balanced, seed 2026. Chỉ fit vocabulary/IDF trên train. Ngưỡng NB là 0,5; SVM là 0. Không chỉnh ngưỡng để khớp demo. Hai mô hình học cùng các tin.

Trên 567 tin ngoài train, chia theo nhóm gần trùng: SVM F1 Spam 99,07%, Precision 99,07%, Recall 99,07%, Ham FPR 0,57% (2/353 Ham). NB F1 Spam 96,63%, Precision 99,50%, Recall 93,93%, Ham FPR 0,28% (1/353 Ham). Trên validation UCI cũ, SVM F1 95,55%; NB F1 90,60%.

Đây là đánh giá phát triển trên các nguồn đã biết, không phải kiểm thử mù trên tin nhắn ngoài thực tế. Nhãn dữ liệu có thể sai hoặc thiếu ngữ cảnh. Không có bảo đảm đúng 100%, không suy ra Ham an toàn hoặc Spam là lừa đảo. Điểm SVM chưa được hiệu chỉnh thành phần trăm tin cậy.

## Kiểm tra và train lại

```powershell
uv run ruff check .
uv run ruff format --check .
uv run mypy src/ai05 ml
uv run pytest -q
node --test tests/web.test.mjs
uv run python scripts/verify_artifacts.py
uv run python scripts/offline_smoke.py
uv run python -m ml.expanded --output results/expanded/run-new
uv run python scripts/activate_expanded.py --run results/expanded/run-new
```

Lệnh train không ghi đè run đã có. references/uci chứa hai trọng số UCI gốc để đối chiếu trên cùng tập ngoài; các trọng số này không phải model đang phục vụ. sources.json và các tệp SOURCE.json khóa hash corpus. Nếu cần tải lại UCI, chạy `uv run python scripts/download_data.py`. Các nguồn ngoài lấy đúng revision và hash ghi trong SOURCE.json; không tự thay tệp khi hash khác.

results/expanded/run-20261107 lưu kế hoạch, hash, ID chia tập, nhãn thật, dự đoán và chỉ số chưa làm tròn. results/report chứa trang báo cáo hai mô hình, mở ở /research/. scripts/verify_artifacts.py kiểm tra registry, tính lại chỉ số và chạy lại dự đoán khi dữ liệu nguồn còn trên máy. Không đổi bản Olympic hoặc dùng điểm mở rộng trong slide báo cáo BTC.

## Chạy Docker trên server

```sh
docker compose -p o1brain-expanded -f deploy/compose.yml build demo
docker compose -p o1brain-expanded -f deploy/compose.yml up -d demo
```

Dịch vụ chỉ bind localhost:18084. Dùng reverse proxy/tunnel sẵn có để công khai. AI05_DATA_DIR có thể trỏ tới thư mục dữ liệu server; mặc định là deploy/data. Thư mục này chứa DB, phải sao lưu riêng và không đóng gói. Image chỉ mang code, trọng số và bằng chứng; không chứa corpus SMS thô, dữ liệu người dùng hoặc khóa.

Khi cập nhật dịch vụ đang có, dùng đúng tên project Docker Compose đang quản lý demo và chỉ cập nhật service demo. Không dùng remove-orphans hoặc dừng các dịch vụ khác. Kiểm tra health/ready và /api/v1/demo/compare trước khi chuyển lưu lượng. Giữ image cũ và cấu hình cũ để rollback.

## Build APK mở rộng

Android 1.0.0 dùng model song ngữ `bilingual-20261107-v2`, tách khỏi model web. versionCode tăng khi cập nhật APK; versionName giữ 1.0.0 theo yêu cầu. Cùng applicationId và khóa ký để cập nhật tại chỗ, giữ dữ liệu ứng dụng.

Cần JDK 17+, Android SDK 35, build-tools 36.0.0 và Gradle. Đặt ANDROID_HOME rồi chạy:

```powershell
uv run python scripts/export_android_model.py
uv run python scripts/export_android_evidence.py
gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

APK debug đã ký nằm tại android/app/build/outputs/apk/debug/app-debug.apk. Để cài vào điện thoại hoặc giả lập, chọn đúng serial từ `adb devices`, rồi dùng `adb -s SERIAL install -r DUONG_DAN_APK`. Không uninstall hoặc xóa dữ liệu nếu chỉ cần cập nhật.

Không train lại khi export. Kiểm tra Python/JVM/Android đối chiếu cùng nhãn, điểm quyết định và model version; không đổi nhãn bằng mẹo trong UI. Các đoạn mô tả nguồn train đã bỏ khỏi UI theo yêu cầu, còn nguồn và số liệu kỹ thuật được giữ trong project để đối chiếu.

## Sử dụng Android

- Chế độ mặc định gửi, nhận và sắp xếp SMS trong kho SMS Android. Cấp vai trò SMS trước; ứng dụng đọc trạng thái bằng RoleManager trên Android 10 trở lên.
- Lọc hỗ trợ giữ ứng dụng SMS hiện tại. Sau khi cấp quyền nhận SMS và bật theo dõi, ứng dụng lưu bản xem mã hóa của tin mới và cảnh báo nghi vấn. Google Messages vẫn nhận SMS. Android không cho ứng dụng hỗ trợ chặn hoặc chuyển tin khỏi ứng dụng mặc định. Chế độ này không đọc hộp thư cũ.
- Bộ lọc tự động xét SMS đến. Tin gửi đi không bị chặn; dùng mục Kiểm tra để phân tích trước khi gửi.
- Tin bình thường ở hộp thư. Điểm trên 0,05510216988743111 là nghi vấn; điểm trên 0,4690409485264919 mới tự chuyển rác khi bật tự chuyển. Điểm SVM không phải xác suất. Các quy tắc về yêu cầu mã xác thực, liên kết làm rối hoặc yêu cầu chuyển tiền chỉ bổ sung cảnh báo. Nhận diện cả khoảng trắng trong http: //, tên miền viết tách như https://notice. org, dạng hxxp/hxxps và tên miền dùng [.] hoặc (dot); giữ nguyên nội dung và điểm mô hình, không mở liên kết.
- Khi chuyển từ hỗ trợ sang mặc định, bản xem trùng khớp số gửi, nội dung và thời điểm gửi của SMS hệ thống chỉ hiển thị một lần. Không xóa SMS hoặc bản xem.
- Lọc tự động của hội thoại xét SMS nhận mới nhất. Chọn “Chuyển vào Tin rác và lọc người gửi” tạo quy tắc cá nhân cho cả tin hiện có và tin mới. “Không phải tin rác, chuyển về hộp thư” bỏ lọc số gửi, bỏ lưu trữ và ưu tiên lựa chọn này trước mô hình. Đánh dấu ưu tiên cũng bỏ quy tắc chặn; không huấn luyện lại model.
- Giữ hội thoại hoặc tin nhắn, hoặc mở menu ba chấm trong chat để báo cáo. Chỉ gửi số điện thoại và loại báo cáo sau khi xác nhận, không gửi nội dung SMS. Có thể tra cứu, thử gửi lại hoặc rút báo cáo tại cùng hộp thoại. Không báo cáo được tên thương hiệu không phải số điện thoại.
- Kết quả cộng đồng đã tra cứu có từ ba người báo cáo chỉ bổ sung cảnh báo trên thiết bị. Không tự tải danh bạ hoặc gửi tất cả số nhận SMS lên máy chủ. Không dùng danh sách số bị tố cáo không rõ nguồn để tự chặn.
- Tin chưa đọc dùng chữ đậm, không đổi nền. Màn soạn mới gợi ý tối đa tám người nhận gần đây, loại số rác, số bị lọc và tin nghi vấn chưa được xác nhận. Giao diện hỗ trợ sáng, tối, cỡ chữ hệ thống và tắt chuyển động trong cài đặt Android.
- Giới hạn hiện tại: tải 3.000 SMS hệ thống gần nhất, 300 MMS và 1.000 bản xem hỗ trợ; thông tin này có trong phần Bộ lọc. Chưa có phân trang lịch sử xa hơn. RCS thuộc ứng dụng đang cung cấp RCS, không phải luồng SMS/MMS của ứng dụng này.

## Model tiếng Việt trên Android

Nguồn tiếng Việt: [Quality-Assured Vietnamese SMS Dataset](https://huggingface.co/datasets/trannguyenthaituan/vietnamese_sms_dataset), revision a90e2bd7e8df2376939658075ba8094dcee488ad, CC BY 4.0. Tác giả và thông báo gốc nằm trong data/external/vietnamese/SOURCE_NOTICE.txt và NOTICE.txt trong APK. Bộ 300 tin tại nguồn vietnamese_sms_phishing_sample không có nhãn; chỉ dùng để loại các nhóm gần trùng khỏi train, không tính chỉ số cho bộ này. Không đưa tin trên điện thoại, mẫu demo hoặc các ví dụ phản biện vào train.

Model học 8.231 tin: 6.391 tiếng Anh từ train mở rộng đã khóa và 1.840 tiếng Việt. Loại trùng và nhóm mâu thuẫn nhãn, chia tiếng Việt theo nhóm gần trùng ở cosine 0,85. Giữ lại 1.599 tin tiếng Anh và 461 tin tiếng Việt để đánh giá phát triển. Ba fold trên train, chia theo nhóm gần trùng, dùng để chọn hai ngưỡng; tiêu chí báo nhầm áp dụng riêng từng ngôn ngữ. Không chọn lại ngưỡng theo kết quả giữ lại. Pipeline TF-IDF ký tự 3–5, tối đa 180.000 đặc trưng; LinearSVC C=1, class_weight=balanced.

Trên tập đánh giá phát triển, tầng tự chuyển rác có Ham FPR tiếng Anh 3/1.257 (0,24%), tiếng Việt 1/336 (0,30%); Precision tương ứng 99,04% và 98,85%. Recall tự chuyển tương ứng 90,94% và 68,80%. Riêng ngưỡng cảnh báo của mô hình có Recall 95,91% tiếng Anh và 90,40% tiếng Việt, Ham FPR 0,24% và 2,38%. Khi tính cả các quy tắc cảnh báo của policy-9 bằng chính bộ suy luận Kotlin trên 2.060 tin giữ lại, tiếng Anh giữ Recall 95,91% và Ham FPR 0,24%; tiếng Việt có Recall 95,20% và Ham FPR 23/336 (6,85%). Cảnh báo chỉ yêu cầu kiểm tra thêm, không tự chuyển rác. Số liệu toàn bộ policy nằm trong policy-evaluation-policy8.json; không được dùng tỷ lệ tự chuyển nhầm 0,30% để mô tả tỷ lệ cảnh báo nhầm. Phần spam chưa đủ điểm cao được giữ lại để người dùng kiểm tra. Đây không phải phép đo bảo đảm trên mọi SMS thực tế; dữ liệu phát triển tiếng Anh đã được xem trước.

```powershell
uv run python -m ml.mobile --output results/mobile/run-new
uv run python -m ml.mobile --export --output results/mobile/run-new
uv run python scripts/export_android_model.py
uv run python scripts/export_android_evidence.py
```

Mỗi run là bất biến; không ghi đè run cũ. artifacts/mobile.json trỏ đến run của Android, không đổi artifacts/registry.json của web. Hai lệnh export mặc định nhận diện mobile.json và kiểm tra hash model, hash nguồn, tính lại chỉ số từ nhãn và điểm đã ghi, rồi tạo assets và fixtures đối chiếu Python/JVM/Android. Kết quả có nhãn, điểm, ID, ngưỡng và kế hoạch chia tập nằm trong results/mobile/bilingual-20261107-v2. Dữ liệu nguồn không được đóng trong APK.

Kiểm thử điện thoại dùng serial cụ thể từ adb devices. SmsRoleTest yêu cầu ứng dụng đang là SMS mặc định. FilteringDeviceTest.observerChoiceActivatesAndStoresOnlyLocalPreview yêu cầu ứng dụng khác đang là mặc định và có RECEIVE_SMS. Các fixture có tên AI05 hoặc số hư cấu +1 202-555-0149; chỉ xóa chính fixture đã tạo, không clear data, không xóa SMS thật. Kiểm thử giao diện và bàn phím không tự gửi SMS có cước. Gửi/nhận SMS và MMS qua nhà mạng phải được kiểm tra riêng với SIM thật.
