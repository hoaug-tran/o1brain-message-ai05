import argparse,joblib
ap=argparse.ArgumentParser();ap.add_argument('--model',default='outputs/AI05/model.joblib');ap.add_argument('--text',required=True,help='English SMS text');a=ap.parse_args()
model=joblib.load(a.model);pred=model.predict([a.text])[0]
prob=model.predict_proba([a.text])[0][list(model.classes_).index('spam')]
print(f'Ket qua: {pred} | P(spam)={prob:.3f}. Canh bao tham khao, khong chac chan la lua dao.')
