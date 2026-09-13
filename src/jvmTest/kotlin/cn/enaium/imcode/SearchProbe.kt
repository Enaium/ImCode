import cn.enaium.imcode.search.SearchService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

fun main() = runBlocking {
    val svc = SearchService()
    var got: List<cn.enaium.imcode.search.SearchHit>? = null
    svc.onResults = { id, hits -> got = hits; println("onResults id=$id hits=${hits.size}") }
    svc.search("/Users/enaium/Projects/untitled", SearchService.Mode.FILE_NAME, "main", 500)
    var waited = 0
    while (got == null && waited < 5000) { delay(50); waited += 50 }
    println("FILE_NAME result: ${got?.size} ${got?.take(3)?.map { it.path }}")
    got = null
    svc.search("/Users/enaium/Projects/untitled", SearchService.Mode.CONTENT, "fun", 500)
    waited = 0
    while (got == null && waited < 5000) { delay(50); waited += 50 }
    println("CONTENT result: ${got?.size} ${got?.take(3)?.map { it.path }}")
    svc.shutdown()
}
