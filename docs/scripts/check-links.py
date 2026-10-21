"""Check generated VitePress page routes and fragments without fetching external links."""
from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import urlsplit,unquote
import json
root=Path(__file__).resolve().parents[1] / '.vitepress/dist'
class Page(HTMLParser):
 def __init__(self):super().__init__();self.ids=set();self.links=[];self.generated=False
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if 'id' in a:self.ids.add(a['id'])
  if tag=='meta' and a.get('name')=='generator' and a.get('content','').startswith('VitePress'):self.generated=True
  if tag=='a' and 'href' in a:self.links.append(a['href'])
pages={}
for p in root.rglob('*.html'):
 doc=Page();doc.feed(p.read_text());pages[p.resolve()]=doc
if not any(p.generated for p in pages.values()):raise SystemExit('No generated VitePress pages found; build the site first.')
errors=[];checked=0;assets=0
for p,doc in pages.items():
 for href in doc.links:
  url=urlsplit(href)
  if url.scheme or url.netloc:continue
  path=unquote(url.path)
  if not path:target=p
  elif path.startswith('/fcitx5-android/'):target=root/path[len('/fcitx5-android/'):]
  elif path.startswith('/'):continue
  else:target=p.parent/path
  if target.is_dir():target=target/'index.html'
  elif not target.suffix:target=target.with_suffix('.html')
  if target.suffix!='.html':
   assets+=1
   if not target.is_file():errors.append({'page':str(p.relative_to(root)),'href':href,'reason':'missing download or asset'})
   continue
  target=target.resolve();checked+=1
  if target not in pages:errors.append({'page':str(p.relative_to(root)),'href':href,'reason':'missing page'})
  elif url.fragment and unquote(url.fragment) not in pages[target].ids:errors.append({'page':str(p.relative_to(root)),'href':href,'reason':'missing anchor'})
print(json.dumps({'pages':len(pages),'links_checked':checked,'asset_links_checked':assets,'errors':len(errors)},ensure_ascii=False))
for e in errors:print(json.dumps(e,ensure_ascii=False))

raise SystemExit(1 if errors else 0)
