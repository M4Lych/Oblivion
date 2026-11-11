package com.m4lych.oblivion.domain

object ServerAddressValidator {
    private val hostnameLabel = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val bracketedIpv6 = Regex("^\\[([0-9A-Fa-f:.%]{2,45})](?::([0-9]{1,5}))?$")

    fun isValid(value: String): Boolean {
        val address = value.trim()
        if (address.isEmpty() || address.length > 255 || address.any(Char::isWhitespace)) return false

        val ipv6 = bracketedIpv6.matchEntire(address)
        if (ipv6 != null) return validPort(ipv6.groupValues.getOrNull(2).orEmpty())

        val colonIndex = address.lastIndexOf(':')
        val host = if (colonIndex >= 0) address.substring(0, colonIndex) else address
        val port = if (colonIndex >= 0) address.substring(colonIndex + 1) else ""
        if (host.isEmpty() || host.contains(':') || host.startsWith('.') || host.endsWith('.') || ".." in host) return false
        if (host.length > 253 || host.split('.').any { !hostnameLabel.matches(it) }) return false
        return colonIndex < 0 || validPort(port)
    }

    private fun validPort(value: String): Boolean {
        if (value.isEmpty()) return true
        if (value.length > 5 || value.any { !it.isDigit() }) return false
        val port = value.toIntOrNull() ?: return false
        return port in 1..65535
    }
}
