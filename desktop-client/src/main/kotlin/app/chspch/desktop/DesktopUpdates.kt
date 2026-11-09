package app.chspch.desktop

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

data class DesktopRelease(val version: String, val notes: String, val url: URI, val bytes: Long, val sha256: String)

object DesktopUpdates {
    const val FEED = "https://tech-supp-test.ru/updates/desktop/latest.json"
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val platform: String get() = if(System.getProperty("os.name").startsWith("Windows"))"windows-x64" else "linux-x64"
    internal fun version(value:String):List<Int>{
        require(value.matches(Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))) { "Неверная версия обновления" }
        return value.split('.').map(String::toInt)
    }
    fun newer(candidate:String,current:String=DESKTOP_VERSION):Boolean {
        val a=version(candidate);val b=version(current)
        return a.indices.firstOrNull{a[it]!=b[it]}?.let{a[it]>b[it]}?:false
    }
    internal fun parse(text:String,target:String=platform):DesktopRelease {
        require(text.length<=65536)
        val root=JSONObject(text)
        require(root.getInt("schemaVersion")==1&&root.getString("product")=="ChspchDesktop")
        val name=root.getString("versionName");version(name)
        val item=root.getJSONObject("artifacts").getJSONObject(target)
        val url=URI(item.getString("url"))
        val extension=if(target=="windows-x64")"zip" else "tar.gz"
        require(url.scheme=="https"&&url.host=="tech-supp-test.ru"&&url.port in listOf(-1,443)&&url.userInfo==null&&url.query==null&&url.fragment==null)
        require(url.path.matches(Regex("/updates/desktop/releases/chspch-${Regex.escape(target)}-${Regex.escape(name)}-[a-f0-9]{12}\\.${Regex.escape(extension)}")))
        val bytes=item.getLong("sizeBytes");require(bytes in 1..(250L*1024*1024))
        val hash=item.getString("sha256");require(hash.matches(Regex("[a-f0-9]{64}")))
        return DesktopRelease(name,root.optString("notes").take(4000),url,bytes,hash)
    }
    fun check():DesktopRelease? {
        val request=HttpRequest.newBuilder(URI(FEED)).timeout(Duration.ofSeconds(20)).GET().build()
        val response=client.send(request,HttpResponse.BodyHandlers.ofInputStream())
        val text=response.body().use{stream->
            check(response.statusCode()==200){"Сервер обновлений временно недоступен"}
            val raw=stream.readNBytes(65537);check(raw.size<=65536);raw.toString(Charsets.UTF_8)
        }
        return parse(text).takeIf{newer(it.version)}
    }
    fun download(release:DesktopRelease,directory:Path,progress:(Int)->Unit):Path {
        Files.createDirectories(directory);StateStore.restrict(directory,true)
        val file=directory.resolve(release.url.path.substringAfterLast('/'))
        val part=Files.createTempFile(directory,"download-",".part");StateStore.restrict(part)
        try {
            check(Files.getFileStore(directory).usableSpace>release.bytes+16*1024*1024){"Недостаточно места для обновления"}
            val connection=release.url.toURL().openConnection() as javax.net.ssl.HttpsURLConnection
            connection.connectTimeout=10000;connection.readTimeout=20000;connection.instanceFollowRedirects=false
            try {
                check(connection.responseCode==200){"Не удалось скачать обновление"}
                connection.inputStream.use{input->Files.newOutputStream(part).use{output->
                    copyVerified(input,output,release.bytes,release.sha256,progress)
                }}
            } finally {connection.disconnect()}
            Files.move(part,file,StandardCopyOption.REPLACE_EXISTING)
            return file
        } finally {Files.deleteIfExists(part)}
    }
    internal fun copyVerified(input:java.io.InputStream,output:java.io.OutputStream,expected:Long,hash:String,progress:(Int)->Unit={}) {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        var total=0L;var last=-1
        while(true){
            val n=input.read(buffer);if(n<0)break
            total+=n;check(total<=expected){"Размер обновления не совпадает"}
            digest.update(buffer,0,n);output.write(buffer,0,n)
            val percent=(total*100/expected).toInt();if(percent!=last){progress(percent);last=percent}
        }
        check(total==expected){"Обновление скачано не полностью"}
        val actual=digest.digest().joinToString(""){"%02x".format(it)}
        check(actual==hash){"Контрольная сумма обновления не совпадает. Повторите загрузку."}
    }
}
