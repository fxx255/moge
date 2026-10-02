package com.moge.app.data.update

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The main agent owns these files; this bounded check guards the installation handoff. */
class UpdateManifestIntegrationTest {
    private fun source(path: String): File = listOf(File("src/main/$path"), File("app/src/main/$path"))
        .firstOrNull { it.isFile } ?: error("Cannot find Android integration source")

    private fun document(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(source(path))

    @Test fun installerPermissionAndReadGrantProviderAreDeclared() {
        val doc = document("AndroidManifest.xml")
        val android = "http://schemas.android.com/apk/res/android"
        val permissions = doc.getElementsByTagName("uses-permission")
        assertTrue((0 until permissions.length).any {
            permissions.item(it).attributes.getNamedItemNS(android, "name")?.nodeValue == "android.permission.REQUEST_INSTALL_PACKAGES"
        })
        val providers = doc.getElementsByTagName("provider")
        val provider = (0 until providers.length).map { providers.item(it) }.single {
            it.attributes.getNamedItemNS(android, "name")?.nodeValue == "androidx.core.content.FileProvider"
        }
        assertEquals("\${applicationId}.files", provider.attributes.getNamedItemNS(android, "authorities").nodeValue)
        assertEquals("false", provider.attributes.getNamedItemNS(android, "exported").nodeValue)
        assertEquals("true", provider.attributes.getNamedItemNS(android, "grantUriPermissions").nodeValue)
    }

    @Test fun updaterExposesOnlyUpdatesSubdirectory() {
        val paths = document("res/xml/file_paths.xml").getElementsByTagName("files-path")
        val update = (0 until paths.length).map { paths.item(it) }.single {
            it.attributes.getNamedItem("name")?.nodeValue == "updates"
        }
        assertEquals("updates/", update.attributes.getNamedItem("path").nodeValue)
    }
}
