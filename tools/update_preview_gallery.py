"""Point the local gallery at native 0.3.2 exports and expose comparison controls."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
page = root / "testcase/output/scenes/index.html"
html = page.read_text(encoding="utf-8")
html = html.replace("scene-previews/", "mesh-fixed/scene-previews-0.3.2/")
html = html.replace("第三张的柱子与屋顶边缘有明显网格裂缝，第一张灯杆附近也有缺口；人物尚未匹配夜景光照。以下保留真实输出，不代表效果已全部验收。",
                    "已按原项目恢复连续网格，正视裂缝已消除。可切换修复前、合成重估和边缘羽化；倾斜时仍可能有单图深度拉伸，人物尚未匹配夜景光照。")
html = html.replace("全图视角抵消了壁纸的 10% 过扫描。", "已取消壁纸隐藏的 10% 过扫描，完整显示场景。")
if "toggleEstimate(this)" not in html:
    html = html.replace('<button onclick="tilt(this)">查看倾斜视角</button>',
                        '<button onclick="tilt(this)">查看倾斜视角</button>'
                        '<button onclick="toggleEstimate(this)">合成重估对比</button>'
                        '<button onclick="toggleBefore(this)">查看修复前</button>'
                        '<button onclick="toggleFeather(this)">查看边缘羽化</button>')
script = """<script>
function focusImage(b){const f=b.closest('.card').querySelector('.photo');f.classList.toggle('focus');b.textContent=f.classList.contains('focus')?'恢复完整画面':'放大人物局部'}
function renderCase(c){
 const old=c.dataset.old==='true',estimated=c.dataset.estimated==='true',feather=c.dataset.feather==='true',tilted=c.classList.contains('tilted');
 const folder=old?'before-mesh-fix/':'mesh-fixed/scene-previews-0.3.2/';
 const name=c.dataset.case+(estimated?'-reestimated':'')+(feather?'-feather':tilted?'-tilt':'')+'.png';
 c.querySelector('.photo img').src=folder+name;c.querySelector('.photo').href=folder+name;
 c.querySelector('[onclick="tilt(this)"]').textContent=tilted?'恢复正视角':'查看倾斜视角';
 c.querySelector('[onclick="toggleEstimate(this)"]').textContent=estimated?'显示原分层':'合成重估对比';
 c.querySelector('[onclick="toggleBefore(this)"]').textContent=old?'切回修复后':'查看修复前';
 c.querySelector('[onclick="toggleFeather(this)"]').textContent=feather?'关闭羽化对比':'查看边缘羽化';
}
function tilt(b){const c=b.closest('.card');c.dataset.feather='false';c.classList.toggle('tilted');renderCase(c)}
function toggleEstimate(b){const c=b.closest('.card');c.dataset.old=c.dataset.feather='false';c.dataset.estimated=c.dataset.estimated==='true'?'false':'true';renderCase(c)}
function toggleBefore(b){const c=b.closest('.card');c.dataset.estimated=c.dataset.feather='false';c.dataset.old=c.dataset.old==='true'?'false':'true';renderCase(c)}
function toggleFeather(b){const c=b.closest('.card');c.dataset.old=c.dataset.estimated='false';c.classList.remove('tilted');c.dataset.feather=c.dataset.feather==='true'?'false':'true';renderCase(c)}
</script>"""
start = html.index("<script>")
end = html.index("</script>", start) + len("</script>")
page.write_text(html[:start] + script + html[end:], encoding="utf-8")
for name in ("1-full-body", "2-upper-body", "3-behind-column"):
    for suffix in ("", "-tilt", "-feather", "-reestimated", "-reestimated-tilt"):
        assert (page.parent / "mesh-fixed/scene-previews-0.3.2" / f"{name}{suffix}.png").is_file()
print(page)
