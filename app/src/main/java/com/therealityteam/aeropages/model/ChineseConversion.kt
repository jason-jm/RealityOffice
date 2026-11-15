package com.therealityteam.aeropages.model

/**
 * Word's 中文简繁转换 (Review ▸ Chinese Conversion): Simplified to Traditional Chinese and back. The characters are the
 * device's to convert ([engine]: Android's ICU transliterators); on top of them, with [vocabulary], the everyday and
 * computing words the mainland and Taiwan say differently (Word's 转换常用词汇): 软件 / 軟體, 网络 / 網路, 打印 / 列印….
 */
object ChineseConversion {
    /** What turns characters one way or the other. */
    interface Engine {
        fun toTraditional(text: String): String
        fun toSimplified(text: String): String
    }

    /** The device's (set as the app starts); null where it has none — then nothing converts. */
    @Volatile var engine: Engine? = null

    val available: Boolean get() = engine != null

    /**
     * Mainland words and the Taiwan words for them, longest first — those marked one-way are not turned back (the
     * Taiwan word is a mainland word too, as 资料: turned back it would be wrong as often as right).
     */
    private class Word(val simplified: String, val traditional: String, val reversible: Boolean = true)

    private val WORDS: List<Word> = listOf(
        Word("笔记本电脑", "筆記型電腦"), Word("智能手机", "智慧型手機"), Word("打印预览", "預覽列印"),
        Word("打印机", "印表機"), Word("服务器", "伺服器"), Word("数据库", "資料庫"), Word("文件夹", "資料夾"),
        Word("出租车", "計程車"), Word("公交车", "公車"), Word("方便面", "泡麵"), Word("西红柿", "番茄", reversible = false),
        Word("软件", "軟體"), Word("硬件", "硬體"), Word("网络", "網路"), Word("信息", "資訊"), Word("程序", "程式"),
        Word("鼠标", "滑鼠"), Word("打印", "列印"), Word("内存", "記憶體"), Word("硬盘", "硬碟"), Word("数据", "資料", reversible = false),
        Word("默认", "預設"), Word("菜单", "選單"), Word("窗口", "視窗"), Word("链接", "連結"), Word("网页", "網頁"),
        Word("博客", "部落格"), Word("短信", "簡訊"), Word("屏幕", "螢幕"), Word("激光", "雷射"), Word("光盘", "光碟"),
        Word("字体", "字型"), Word("图标", "圖示"), Word("界面", "介面"), Word("质量", "品質"), Word("粘贴", "貼上"),
        Word("剪切", "剪下"), Word("撤销", "復原"), Word("视频", "視訊"), Word("音频", "音訊"), Word("宽带", "寬頻"),
        Word("在线", "線上"), Word("离线", "離線"), Word("登录", "登入"), Word("用户", "使用者"), Word("优化", "最佳化"),
        Word("土豆", "馬鈴薯", reversible = false), Word("自行车", "腳踏車"), Word("地铁", "捷運"),
    ).sortedByDescending { it.simplified.length }

    private val BACK: List<Word> = WORDS.filter { it.reversible }.sortedByDescending { it.traditional.length }

    /** [text] in Traditional ([toTraditional]) or Simplified characters — the words first, with [vocabulary]. */
    fun convert(text: String, toTraditional: Boolean, vocabulary: Boolean = true): String {
        val e = engine ?: return text
        if (text.none { it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF }) return text
        var t = text
        if (vocabulary) t = words(t, toTraditional)
        return if (toTraditional) e.toTraditional(t) else e.toSimplified(t)
    }

    /** The words of [text] replaced, longest first, each place once. */
    private fun words(text: String, toTraditional: Boolean): String {
        val list = if (toTraditional) WORDS else BACK
        val sb = StringBuilder()
        var i = 0
        outer@ while (i < text.length) {
            for (w in list) {
                val from = if (toTraditional) w.simplified else w.traditional
                if (text.startsWith(from, i)) {
                    sb.append(if (toTraditional) w.traditional else w.simplified)
                    i += from.length
                    continue@outer
                }
            }
            sb.append(text[i]); i++
        }
        return sb.toString()
    }
}
