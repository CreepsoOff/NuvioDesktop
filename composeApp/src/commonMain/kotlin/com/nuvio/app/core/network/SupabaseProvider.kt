package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppVersionConfig
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders

object SupabaseProvider {
    val isConfigured: Boolean
        get() = SupabaseConfig.URL.isNotBlank() && SupabaseConfig.ANON_KEY.isNotBlank()

    @OptIn(SupabaseInternal::class)
    val client by lazy {
        check(isConfigured) {
            "Supabase is not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY in local.properties."
        }
        val userAgent = "NuvioDesktop/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}"
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.URL,
            supabaseKey = SupabaseConfig.ANON_KEY,
        ) {
            httpConfig {
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, userAgent)
                }
            }
            install(Auth)
            install(Postgrest)
            install(Functions)
        }
    }
}
