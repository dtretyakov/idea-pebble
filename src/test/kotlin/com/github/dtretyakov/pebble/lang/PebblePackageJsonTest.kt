package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.testing.IdeTestCase

class PebblePackageJsonTest : IdeTestCase() {

    fun `test platforms complete inside targetPlatforms`() {
        myFixture.configureByText("package.json", """{"pebble": {"targetPlatforms": ["e<caret>"]}}""")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "emery")
    }

    fun `test an Alloy project is offered only the platforms Alloy runs on`() {
        myFixture.configureByText("package.json", """{"pebble": {"projectType": "moddable", "targetPlatforms": ["<caret>"]}}""")
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertContainsElements(offered, "emery", "gabbro")
        assertDoesntContain(offered, "basalt", "aplite")
    }

    fun `test project types complete`() {
        myFixture.configureByText("package.json", """{"pebble": {"projectType": "<caret>"}}""")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "native", "moddable", "package")
    }

    fun `test the only match is inserted`() {
        myFixture.configureByText("package.json", """{"pebble": {"projectType": "mod<caret>"}}""")
        myFixture.completeBasic()
        myFixture.checkResult("""{"pebble": {"projectType": "moddable<caret>"}}""")
    }

    fun `test nothing is offered outside the pebble section`() {
        myFixture.configureByText("package.json", """{"targetPlatforms": ["e<caret>"]}""")
        myFixture.completeBasic()
        assertDoesntContain(myFixture.lookupElementStrings.orEmpty(), "emery")
    }

    fun `test mistakes are reported`() {
        myFixture.enableInspections(PebblePackageJsonInspection())
        myFixture.configureByText(
            "package.json",
            """
            {"pebble": {
              "projectType": "moddable",
              "targetPlatforms": ["emery", <error descr="Unknown platform 'emerald'. Pebble platforms are aplite, basalt, chalk, diorite, flint, emery, gabbro.">"emerald"</error>, <warning descr="Alloy does not run on basalt; it runs on emery and gabbro.">"basalt"</warning>]
            }}
            """.trimIndent(),
        )
        myFixture.checkHighlighting()
    }

    fun `test an unknown project type is an error`() {
        myFixture.enableInspections(PebblePackageJsonInspection())
        myFixture.configureByText(
            "package.json",
            """{"pebble": {"projectType": <error descr="pebble-tool cannot build a 'pebblejs' project. Project types are native, moddable, package, rocky.">"pebblejs"</error>}}""",
        )
        myFixture.checkHighlighting()
    }

    fun `test other JSON files are left alone`() {
        myFixture.enableInspections(PebblePackageJsonInspection())
        myFixture.configureByText("other.json", """{"pebble": {"targetPlatforms": ["emerald"]}}""")
        myFixture.checkHighlighting()
    }

    fun `test a resource is found as named or as a platform variant, and reported when neither`() {
        myFixture.enableInspections(PebblePackageJsonInspection())
        myFixture.addFileToProject("resources/images/icon.png", "")
        myFixture.addFileToProject("resources/images/logo~color.png", "")
        myFixture.addFileToProject("resources/images/logo~bw.png", "")
        myFixture.addFileToProject("resources/images/badge-big.png", "")
        myFixture.configureByText(
            "package.json",
            """
            {"pebble": {"resources": {"media": [
              {"type": "bitmap", "name": "ICON", "file": "images/icon.png"},
              {"type": "bitmap", "name": "LOGO", "file": "images/logo.png"},
              {"type": "bitmap", "name": "BADGE", "file": <error descr="No resources/images/badge.png, nor a variant of it such as images/badge~color.png: the build will fail.">"images/badge.png"</error>}
            ]}}}
            """.trimIndent(),
        )
        myFixture.checkHighlighting()
    }

    fun `test a message key is used from C, from JavaScript or from Clay's configuration`() {
        myFixture.enableInspections(PebblePackageJsonInspection())
        myFixture.addFileToProject("src/c/main.c", "int t = MESSAGE_KEY_Temperature; int r = MESSAGE_KEY_Readings + 1;")
        myFixture.addFileToProject("src/pkjs/index.js", "Pebble.sendAppMessage({ City: 'Paris' });")
        myFixture.addFileToProject("src/pkjs/config.json", """[{"type": "toggle", "messageKey": "Celsius"}]""")
        myFixture.configureByText(
            "package.json",
            """
            {"pebble": {"messageKeys": ["Temperature", "Readings[4]", "City", "Celsius", <warning descr="Message key 'dummy' is used nowhere in src/ or worker_src/.">"dummy"</warning>]}}
            """.trimIndent(),
        )
        myFixture.checkHighlighting()
    }

    fun `test the object form of message keys is checked by name`() {
        myFixture.enableInspections(PebblePackageJsonInspection())
        myFixture.addFileToProject("src/c/main.c", "int t = MESSAGE_KEY_Temperature;")
        myFixture.configureByText(
            "package.json",
            """{"pebble": {"messageKeys": {"Temperature": 0, <warning descr="Message key 'Humidity' is used nowhere in src/ or worker_src/.">"Humidity"</warning>: 1}}}""",
        )
        myFixture.checkHighlighting()
    }
}
