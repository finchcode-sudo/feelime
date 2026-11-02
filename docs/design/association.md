# 中文联想（bigram 统计表）

选词上屏后，候选条给出「下一个词」的联想候选（issue #2，2026-09 立项）。

## 1. 数据

- `engine-data/assoc/zh_bigram.tsv`：裸 TSV，`prev<TAB>next<TAB>count`。
  不用 .gz 交付——aapt2 打包会解压 .gz 资产并去掉后缀，运行时按原路径
  找不到文件会让整条部署链路静默失败；APK 对条目本身做 DEFLATE，体积不吃亏。
- 语料：LCCC 对话语料（预分词，标点独立成 token，标点断链）。
- 生成：`scripts/build-association-data.py`（确定性剪枝/排序，重跑字节稳定）。
  参数：每前词 top-8、单对 ≥3 次、前词总计 ≥12 次、纯中文 1-4 字词。
- 部署：进 `engine-data/MANIFEST.json`（bytes+sha256），随 EngineDataStore
  的版本目录哈希校验部署，与其他引擎资产同一通道。

## 2. 查询（AssociationStore）

- 加载：hello/开关变化时后台预热（裸 TSV 解析为 `HashMap<prev,
  List<next>>`，文件内序即质量序，直接截断 TOP_K）；预热未完成时
  `next()` 同步解析兜底（双检锁，首次约 0.5s）——联想表查询不阻塞
  输入主链路，宁可首查慢一次也不返回假空。
- 命中：取上屏文本的**最长可用后缀**（4→1 字）查表。上屏可能是整句，
  不需要分词器。

## 3. 触发与展示

- 挂点在 service 的 engine listener：`event.state.commit` 非空且模式为
  全拼/双拼/T9/笔画且开关开启时，commit 事件尾部经独立的
  `Feelime.onAssoc` 通道推送词列表。其他事件不推，键盘保留现有联想
  直到组合开始。
- **跟随编辑框真实内容（2026-11-02，用户反馈「联想不变」）**：只靠中文
  commit 事件推联想的话，英文/直出/符号上屏、退格、粘贴、点选移动光标都不
  触发推送，联想词会一直停在上一次中文上屏的后继上。两处兜底：① 非中文
  模式的 commit 事件直接推空清联想；② `onUpdateSelection`（非组合区、
  选区变化）合并 60ms 后按 `getTextBeforeCursor(4)` 重算——以汉字词结尾给
  后继，否则清空；选区非空清空；敏感框/终端型框/面板输入不读编辑框；宿主
  返回 null 时保持现状。键盘侧 `onAssoc` 在组合期间丢弃迟到的联想词。
- 键盘：组合（composing）非空时联想让位给引擎候选；组合为空时展示联想词，
  样式与引擎候选完全同款（用户定稿 2026-09-20：不做灰字/圆点等降调标识，
  class `candidate assoc` 仅作测试定位用）。
- 清空时机：组合开始、编辑器切换（onStartInput 推空列表）、**键盘收起**
  （onFinishInputView 推空，onStartInputView 再兜一次——同编辑器收起再弹出
  不走 onStartInput，不清的话联想词与工具栏让位态原样残留，device 复现
  2026-09-20）、模式切换、关闭开关。
- 点击联想词：`Native.commitAssoc(word, token)` → coordinator.pasteExternal
  写入编辑器 → 立即以该词为前词计算下一轮联想并推送（连续联想）。

## 3a. 英文补全与学习（2026-11-02，用户：「Gboard 都知道我的喜好」）

英文 Direct 模式逐键上屏、没有组合，原来候选条空着。复用联想候选条通道：

- **候选**：`refreshAssociationFromEditor` 在当前模式为 DIRECT 时，取光标前
  48 字（`EnglishSuggestStore.WINDOW`），`trailingPrefix` 提取正在输入的单词
  前缀（≥2 字母；邮箱/网址/数字/文件名里的不算，汉字、空白、引号、括号、
  连字符之后算），`suggest` 排序：已学词（次数降序）→ 内置技术词
  （`DEFAULT_ENGLISH_WORDS`）→ `english-words.txt` 高频词，至多 6 条，大小写
  跟随前缀（首字母大写→首字母大写，全大写→全大写）。经 `onAssoc` 推送，
  payload 带 `kind:"english"` 和当时的 `prefix`。
- **点选**：键盘调 `Native.commitEnglishSuggestion(word, prefix, token)`；原生
  先核对光标前文本的前缀仍是 `prefix`（候选条可能是上一键的残影，对不上就
  忽略），再 `coordinator.replaceBeforeCursor` 删前缀、上屏「词 + 空格」。
- **学习**：打字（选区向前正好走一个字符）使光标前文本以空格/标点收尾时，
  记下刚收尾的单词（`finishedWord`）；点补全候选也记一次。词存
  `files/english-learned.tsv`（key、次数、词形），上限 3000，超出删次数最低
  的 10%；不进备份、不外传。词形：带内部大写（iPhone/GitHub）或短全大写
  （APK）原样记，其余小写，句首大写不污染。
- **不做**：密码框/终端框、URL/邮箱/过滤类输入框、声明
  `TYPE_TEXT_FLAG_NO_SUGGESTIONS` 的输入框——既不补全也不学习。
- **键盘侧**：英文候选与中文联想共用 `assocWords`，× 清除、工具栏让位同口径；
  「退格清联想」开关（#46）**不拦**英文候选——它跟着光标前的字走，退格要
  照常删字。
- **开关**：沿用「中文联想」开关（`association_on`，默认关），没有单独入口；
  关掉即不补全、不学习。

## 4. 开关

- `feelime_keyboard` 偏好 `association_on`，**默认关**（不惊扰现有用户，
  设备套件行为保持确定性）。
- 设置页「输入」卡「中文联想」开关 → `setAssociation(on, token)` →
  ACTION_KEYBOARD_PREFS_CHANGED 广播 → IME 重推 hello（带 associationOn）。
