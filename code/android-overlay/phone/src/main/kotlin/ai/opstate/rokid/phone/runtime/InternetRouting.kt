package ai.opstate.rokid.phone.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.Inet4Address
import java.net.URI

/** Keep harness traffic on a validated Internet route while CXR owns a separate P2P link. */
class InternetRouting(context: Context) {
    private val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    fun client(endpoint: String,base: OkHttpClient): OkHttpClient {
        val host=URI(endpoint).host ?: throw IOException("invalid harness host")
        if(host in setOf("127.0.0.1","localhost","::1")) return base
        val networks=manager.allNetworks.toList()
        val privateAddress=if(host.matches(Regex("[0-9.]{7,15}"))) InetAddress.getByName(host).takeIf {it.isSiteLocalAddress} else null
        val internet=networks.filter {candidate-> manager.getNetworkCapabilities(candidate)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }==true}
        val specific=if(privateAddress!=null) networks.mapNotNull {candidate->
            manager.getLinkProperties(candidate)?.routes?.filter {it.destination.prefixLength>0 && it.matches(privateAddress)}
                ?.maxOfOrNull {it.destination.prefixLength}?.let {candidate to it}
        }.maxByOrNull {it.second}?.first else null
        // Private lab hosts can be routed by a Wi-Fi gateway even without a matching subnet route.
        // A P2P network with no validated Internet route must never win this fallback.
        val network=specific ?: internet.firstOrNull {it==manager.activeNetwork} ?: internet.firstOrNull()
        if(network==null) throw IOException("no network route to the configured harness")
        return base.newBuilder().socketFactory(network.socketFactory).dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val resolved=network.getAllByName(hostname).toList()
                // This phone has repeatedly timed out opening DashScope's IPv6 address while the same
                // model calls on IPv4 complete. Keep IPv6 in DNS and preserve IPv6-only links.
                return if(hostname=="dashscope.aliyuncs.com" && resolved.any {it is Inet4Address})
                    resolved.sortedByDescending {it is Inet4Address} else resolved
            }
        }).build()
    }
}
