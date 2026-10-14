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
}
