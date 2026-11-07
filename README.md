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

## Nguồn và phần thực hiện

Dữ liệu: bộ dữ liệu tin nhắn UCI theo chỉ định của BTC. Mã baseline BTC: `vendor/btc/AI05`. Phiên bản thư viện và hash nằm trong `uv.lock`, `requirements.txt`; font Manrope và mã MMS có thông báo bản quyền đi kèm. Không sử dụng mô hình tin nhắn pretrained bên ngoài.

Nhóm thực hiện kiểm tra dữ liệu, so sánh và chọn cấu hình, báo cáo, API, web và tích hợp Android. Quy tắc loại trùng và thuật toán baseline do BTC cung cấp. OpenAI Codex được dùng để hỗ trợ lập trình, kiểm thử, biên tập; không tham gia suy luận tin nhắn. Phần dùng lại và công cụ hỗ trợ được công khai theo mục 2.1 của đề.

**Giới hạn:** Điểm SVM là điểm quyết định có dấu, không phải xác suất đã hiệu chỉnh. Điểm NB cũng chưa hiệu chỉnh. Ham không có nghĩa tin nhắn an toàn; Spam không đồng nghĩa với lừa đảo. Đây là nguyên mẫu nghiên cứu, chưa đủ bằng chứng để triển khai trong nhà trường.
