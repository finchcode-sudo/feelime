import sys,os
p=os.path.expanduser('~/feelime/app/src/main/assets/keyboard/keyboard.js')
s=open(p,encoding='utf-8').read()
old="""            pages.addEventListener('touchend', cancelTip, { passive: true });
            pages.addEventListener('touchcancel', cancelTip, { passive: true });
"""
new="""            // 手一离开立刻收起详情（click 仍由 emojiTipFired 拦住，不会误打出表情）。
            const releaseTip = () => { cancelTip(); this.hideEmojiTip(); };
            pages.addEventListener('touchend', releaseTip, { passive: true });
            pages.addEventListener('touchcancel', releaseTip, { passive: true });
"""
if s.count(old)!=1:
    print('锚点缺失，未修改'); sys.exit(1)
open(p,'w',encoding='utf-8').write(s.replace(old,new)); print('OK')
