import com.example.mhtmllens.mhtml.MhtmlParser
import java.io.File

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: ParserSmokeTest <archive-file>" }
    val file = File(args[0])
    val archive = MhtmlParser.parse(file.readBytes())
    println("title=${archive.title}")
    println("charset=${archive.rootCharset}")
    println("root=${archive.rootUrl}")
    println("parts=${archive.parts.size}")
    println("aliases=${archive.aliases.size}")
    val rootText = MhtmlParser.decodePartText(archive.root).first
    println("containsChinese=${rootText.contains("你好！看起来您对这段对话很感兴趣")}")
    println("containsMojibake=${rootText.contains("ä½") || rootText.contains("ï¼")}")
}
