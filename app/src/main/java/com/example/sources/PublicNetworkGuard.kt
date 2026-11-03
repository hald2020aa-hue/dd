package com.example.sources

import java.io.IOException
import java.net.InetAddress

/** Protects URL fetches against DNS-rebinding, local IPv4 and IPv6 addresses. */
object PublicNetworkGuard {
    fun filterPublic(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty() || addresses.any { !isPublic(it) })
            throw IOException("Source hostname resolved to a private, reserved or local address")
        return addresses
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            return when {
                a == 0 || a == 10 || a == 127 || a >= 224 -> false
                a == 100 && b in 64..127 -> false // carrier-grade NAT
                a == 169 && b == 254 -> false
                a == 172 && b in 16..31 -> false
                a == 192 && (b == 168 || (b == 0 && c == 0) || (b == 0 && c == 2)) -> false
                a == 198 && (b in 18..19 || (b == 51 && c == 100)) -> false
                a == 203 && b == 0 && c == 113 -> false
                else -> true
            }
        }
        if (bytes.size != 16) return false
        // IPv6 unique local fc00::/7; link local and multicast are handled above.
        if ((bytes[0] and 0xfe) == 0xfc) return false
        // Documentation (2001:db8::/32) is not a public destination.
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) return false
        return true
    }
}
