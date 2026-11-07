import argparse,json
from pathlib import Path
import joblib,pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.naive_bayes import MultinomialNB
from sklearn.pipeline import make_pipeline
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score,classification_report,confusion_matrix,f1_score,precision_score,recall_score

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--data',required=True,help='UCI SMSSpamCollection, tab-separated, no header');ap.add_argument('--out',default='outputs/AI05');a=ap.parse_args()
 df=pd.read_csv(a.data,sep='\t',header=None,names=['label','text'],encoding='latin-1',engine='python')
 df=df[df.label.isin(['ham','spam'])].dropna().copy()
 # Identical messages cannot appear in train and val.
 df['normalized']=df.text.astype(str).str.strip().str.lower()
 bad=df.groupby('normalized').label.nunique(); ambiguous=set(bad[bad>1].index)
 df=df[~df.normalized.isin(ambiguous)].drop_duplicates('normalized')
 tr,va=train_test_split(df,test_size=.2,stratify=df.label,random_state=2026)
 model=make_pipeline(TfidfVectorizer(ngram_range=(1,2),min_df=1,sublinear_tf=True),MultinomialNB(alpha=0.5))
 model.fit(tr.text,tr.label); pred=model.predict(va.text)
 ham=va.label.to_numpy()=='ham';fp=((pred=='spam')&ham).sum()
 m={'accuracy':float(accuracy_score(va.label,pred)),'macro_f1':float(f1_score(va.label,pred,average='macro')),
    'spam_f1':float(f1_score(va.label,pred,pos_label='spam')),
    'spam_precision':float(precision_score(va.label,pred,pos_label='spam')),
    'spam_recall':float(recall_score(va.label,pred,pos_label='spam')),
    'ham_false_positive_rate':float(fp/max(1,ham.sum())),
    'classification_report':classification_report(va.label,pred,output_dict=True,zero_division=0),
    'confusion_matrix':confusion_matrix(va.label,pred,labels=['ham','spam']).tolist(),
    'labels':['ham','spam'],'split':'dedup exact-normalized, stratified dev 80/20 seed 2026',
    'note':'Bo du lieu tieng Anh; khong suy luan kha nang nhan dien lua dao tieng Viet.'}
 out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
 joblib.dump(model,out/'model.joblib');(out/'metrics.json').write_text(json.dumps(m,indent=2,ensure_ascii=False),encoding='utf-8')
 va[['text']].head(8).to_csv(out/'demo_messages.csv',index=False)
 print('AI05 OK',round(m['macro_f1'],3),'spam f1:',round(m['spam_f1'],3))
if __name__=='__main__':main()
