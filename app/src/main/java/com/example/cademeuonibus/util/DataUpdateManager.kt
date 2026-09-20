package com.example.cademeuonibus.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class AppConfig(
    val intros_list: List<String>? = null
)

class DataUpdateManager(private val context: Context) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val PREFS_NAME = "update_prefs"
    
    // Chaves para salvar as datas de modificação
    private val KEY_GTFS_DATE = "gtfs_last_mod"
    private val KEY_SPRITES_DATE = "sprites_last_mod"
    private val KEY_INTROS_LIST = "intros_list_json"
    
    private val BASE_URL = "https://raw.githubusercontent.com/Shiruso/cademeuonibus-data/main/data/"
    private val CONFIG_URL = "${BASE_URL}config.json"
    private val DATABASE_ZIP_URL = "${BASE_URL}gtfs_rio.zip"
    private val SPRITES_URL = "${BASE_URL}bus_sprites.png"
    private val INTROS_DIR_URL = "${BASE_URL}Intros/"
    
    private val DB_NAME = "gtfs_rio_final.db"
    private val DB_FILE_IN_ZIP = "gtfs_rio.db"
    private val SPRITES_NAME = "bus_sprites.png"

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedIntrosList(): List<String>? {
        val json = prefs.getString(KEY_INTROS_LIST, null) ?: return null
        return try { gson.fromJson(json, Array<String>::class.java).toList() } catch (e: Exception) { null }
    }

    suspend fun checkForUpdates(onStatusUpdate: (String) -> Unit): Boolean {
        try {
            onStatusUpdate("Verificando atualizações...")
            
            val config = withContext(Dispatchers.IO) { fetchConfig() }
            var mudouAlgo = false

            // 1. Verificar GTFS
            val remoteGtfsDate = withContext(Dispatchers.IO) { getRemoteLastModified(DATABASE_ZIP_URL) }
            if (remoteGtfsDate != prefs.getString(KEY_GTFS_DATE, "") || !databaseExists()) {
                onStatusUpdate("Sincronizando dados...")
                if (withContext(Dispatchers.IO) { downloadAndUnzipDatabase() }) {
                    prefs.edit().putString(KEY_GTFS_DATE, remoteGtfsDate).apply()
                    mudouAlgo = true
                }
            }

            // 2. Verificar Sprites
            val remoteSpritesDate = withContext(Dispatchers.IO) { getRemoteLastModified(SPRITES_URL) }
            if (remoteSpritesDate != prefs.getString(KEY_SPRITES_DATE, "") || !spritesExist()) {
                onStatusUpdate("Atualizando visual...")
                if (withContext(Dispatchers.IO) { downloadFile(SPRITES_URL, SPRITES_NAME) }) {
                    prefs.edit().putString(KEY_SPRITES_DATE, remoteSpritesDate).apply()
                    mudouAlgo = true
                }
            }

            // 3. Verificar Intro Única (Comparação Simples)
            val remoteIntro = config?.intros_list?.firstOrNull()
            val localIntro = getSavedIntrosList()?.firstOrNull()

            if (remoteIntro != null && remoteIntro != localIntro) {
                onStatusUpdate("Preparando nova capa...")
                if (withContext(Dispatchers.IO) { downloadSingleIntro(remoteIntro) }) {
                    prefs.edit().putString(KEY_INTROS_LIST, gson.toJson(listOf(remoteIntro))).apply()
                    mudouAlgo = true
                }
            }

            onStatusUpdate("Tudo pronto!")
            delay(500)
            return mudouAlgo
        } catch (e: Exception) {
            Log.e("DataUpdateManager", "Erro na atualização: ${e.message}")
            onStatusUpdate("Tudo pronto!")
        }
        return false
    }

    private fun downloadSingleIntro(introName: String): Boolean {
        val introsDir = File(context.filesDir, "Intros")
        if (!introsDir.exists()) introsDir.mkdirs()
        
        // LIMPEZA: Apaga TUDO que tem na pasta para deixar apenas a nova imagem
        introsDir.listFiles()?.forEach { it.delete() }

        return downloadFile("${INTROS_DIR_URL}$introName", "Intros/$introName")
    }

    private fun fetchConfig(): AppConfig? {
        return try {
            val request = Request.Builder().url(CONFIG_URL).header("Cache-Control", "no-cache").build()
            val response = client.newCall(request).execute()
            response.use { 
                if (!it.isSuccessful) null
                else gson.fromJson(it.body?.string(), AppConfig::class.java)
            }
        } catch (e: Exception) { null }
    }

    private fun getRemoteLastModified(url: String): String {
        return try {
            // Faz uma requisição HEAD (só pega o cabeçalho, não baixa o arquivo)
            val request = Request.Builder().url(url).head().header("Cache-Control", "no-cache").build()
            val response = client.newCall(request).execute()
            response.use { 
                it.header("Last-Modified") ?: it.header("Content-Length") ?: ""
            }
        } catch (e: Exception) { "" }
    }

    private fun databaseExists() = context.getDatabasePath(DB_NAME).exists()
    private fun spritesExist() = File(context.filesDir, SPRITES_NAME).exists()

    private fun downloadFile(url: String, fileName: String): Boolean {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val file = File(context.filesDir, fileName)
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                return true
            }
        } catch (e: Exception) {
            Log.e("DataUpdateManager", "Erro ao baixar $fileName: ${e.message}")
        }
        return false
    }

    private fun downloadIntros(introsList: List<String>): Boolean {
        // Método obsoleto, mantido para evitar erros de compilação durante a transição
        return true
    }

    private fun downloadAndUnzipDatabase(): Boolean {
        try {
            val request = Request.Builder().url(DATABASE_ZIP_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                
                val inputStream = response.body?.byteStream() ?: return false
                val zipInputStream = ZipInputStream(inputStream)
                var entry: ZipEntry? = zipInputStream.nextEntry
                
                while (entry != null) {
                    if (entry.name == DB_FILE_IN_ZIP) {
                        val dbFile = context.getDatabasePath(DB_NAME)
                        dbFile.parentFile?.mkdirs()
                        
                        FileOutputStream(dbFile).use { outputStream ->
                            zipInputStream.copyTo(outputStream)
                        }
                        zipInputStream.closeEntry()
                        return true
                    }
                    entry = zipInputStream.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e("DataUpdateManager", "Erro no download/unzip: ${e.message}")
        }
        return false
    }
}
