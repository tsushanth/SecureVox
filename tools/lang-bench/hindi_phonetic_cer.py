"""CER between the romanised reference and the romanised hypothesis after a phonetic fold (long vowels, ph/f, w/v,
aspirates collapse), so Devanagari spelling variants and Roman Hinglish spellings can be compared.
The romaniser is a Python port of android/.../whisper/RomanHindi.kt. Not a standard metric and more lenient than WER.
Usage: python hindi_phonetic_cer.py hindi_prompts_raw.json"""
import json, re, unicodedata, jiwer, sys
C={'क':'k','ख':'kh','ग':'g','घ':'gh','ङ':'n','च':'ch','छ':'chh','ज':'j','झ':'jh','ञ':'n','ट':'t','ठ':'th','ड':'d','ढ':'dh','ण':'n','त':'t','थ':'th','द':'d','ध':'dh','न':'n','प':'p','फ':'ph','ब':'b','भ':'bh','म':'m','य':'y','र':'r','ल':'l','व':'v','श':'sh','ष':'sh','स':'s','ह':'h','ळ':'l'}
NK={'क':'q','ख':'kh','ग':'g','ज':'z','ड':'r','ढ':'rh','फ':'f','य':'y'}
IV={'अ':'a','आ':'aa','इ':'i','ई':'ee','उ':'u','ऊ':'oo','ऋ':'ri','ए':'e','ऐ':'ai','ओ':'o','औ':'au','ऑ':'o','ऍ':'e'}
M={'ा':'aa','ि':'i','ी':'ee','ु':'u','ू':'oo','ृ':'ri','े':'e','ै':'ai','ो':'o','ौ':'au','ॉ':'o','ॅ':'e'}
LAB=set('पफबभम')
def word(raw):
    w=unicodedata.normalize("NFD",raw); U=[]; i=0
    while i<len(w):
        c=w[i]
        if c in C:
            r=C[c]; i+=1
            if i<len(w) and w[i]=='़': r=NK.get(c,r); i+=1
            u={'k':'c','r':r,'v':'','vir':False,'inh':False,'del':False}
            if c=='ञ' and U and U[-1]['k']=='c' and U[-1]['vir'] and U[-1]['r']=='j': U[-1]['r']='g'; u['r']='y'
            if i<len(w) and w[i] in M: u['v']=M[w[i]]; i+=1
            elif i<len(w) and w[i]=='्': u['vir']=True; i+=1
            else: u['inh']=True
            U.append(u)
        elif c in IV: U.append({'k':'v','v':IV[c],'r':'','inh':False,'del':False,'vir':False}); i+=1
        elif c in M: U.append({'k':'v','v':M[c],'r':'','inh':False,'del':False,'vir':False}); i+=1
        elif c in 'ंँ':
            nx=next((w[k] for k in range(i+1,len(w)) if w[k]!='़'),None)
            U.append({'k':'n','r':'m' if (c=='ं' and nx in LAB) else 'n','v':'','inh':False,'del':False,'vir':False}); i+=1
        elif c=='ः': U.append({'k':'n','r':'h','v':'','inh':False,'del':False,'vir':False}); i+=1
        else: i+=1
    hv=lambda u: u['k']=='v' or (u['k']=='c' and (u['v'] or (u['inh'] and not u['del'])))
    for i in reversed(range(len(U))):
        u=U[i]
        if u['k']!='c' or not u['inh']: continue
        if i==len(U)-1:
            if any(hv(x) for x in U[:i]): u['del']=True
        elif i>0:
            p,n=U[i-1],U[i+1]
            if p['k']!='n' and hv(p) and n['k']!='n' and hv(n): u['del']=True
    out=''
    for i,u in enumerate(U):
        last=i==len(U)-1
        if u['k']=='n': out+=u['r']
        elif u['k']=='v': out+=u['v']
        else:
            out+=u['r']
            if u['v']: out+= 'a' if (last and u['v']=='aa') else ('i' if last and u['v']=='ee' else u['v'])
            elif u['inh'] and not u['del']: out+='a'
    return out.replace('chchh','cch')
def roman(s):
    return re.sub(r'[ऀ-ॿ]+', lambda m: word(m.group(0)), s)
def fold(s):
    s=unicodedata.normalize("NFKC",s).lower()
    s="".join(c if (c.isalnum() or c==' ') else ' ' for c in s)
    s=re.sub(r'(.)\1+',r'\1',s)           # long vowels / doubles
    for a,b in [('w','v'),('ph','f'),('z','j'),('ee','i'),('ii','i'),('oo','u'),('uu','u'),('ey','e'),('ai','e'),('au','o'),('kh','k'),('gh','g'),('bh','b'),('dh','d'),('th','t'),('jh','j'),('chh','ch'),('sh','s'),('q','k'),('x','ks')]:
        s=s.replace(a,b)
    s=re.sub(r'(.)\1+',r'\1',s)
    return "".join(s.split())
d=json.load(open(sys.argv[1]))
for k,v in d.items():
    refs=[fold(roman(r["ref"])) for r in v["rows"]]; hyps=[fold(roman(r["hyp"])) for r in v["rows"]]
    print(k,"phonetic-folded CER",round(jiwer.cer(refs,hyps)*100,1))
