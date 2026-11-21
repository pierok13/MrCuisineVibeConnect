package app.toque.core

import app.toque.core.recipes.Diet
import app.toque.core.recipes.DietCheck
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeFilter
import app.toque.core.recipes.SearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tri « sans gluten » / « sans lactose » d'après les étiquettes et les ingrédients. */
class DietTest {
    private fun recipe(id: String, vararg names: String, tags: List<String> = emptyList()) = Recipe(
        id = id, title = id, tags = tags,
        ingredients = listOf(IngredientGroup(items = names.map { Ingredient(name = it) })),
    )

    private fun gf(r: Recipe) = DietCheck.fits(r, Diet.GLUTEN_FREE)
    private fun lf(r: Recipe) = DietCheck.fits(r, Diet.LACTOSE_FREE)

    @Test fun gluten() {
        assertTrue(gf(recipe("risotto", "riz arborio", "oignon", "parmesan", "bouillon de légumes")))
        assertFalse(gf(recipe("gâteau", "farine", "sucre", "œufs")))
        assertFalse(gf(recipe("bolo", "Spaghettis", "tomates")))
        assertFalse(gf(recipe("taboulé", "boulgour", "persil")))
        assertFalse(gf(recipe("pain", "Farine de blé T55")))
        // Farines et pâtes sans blé, mentions « sans gluten ».
        assertTrue(gf(recipe("crêpes", "farine de sarrasin", "farine de riz", "lait")))
        assertTrue(gf(recipe("pad thaï", "nouilles de riz", "pâte de curry rouge", "tamari")))
        assertTrue(gf(recipe("gâteau sg", "farine sans gluten", "pâtes sans gluten")))
        assertTrue(gf(recipe("galette", "farine de blé noir")))
        // Étiquette du site : les ingrédients ambigus (pâtes…) sont acceptés, pas le blé.
        assertTrue(gf(recipe("pâtes sg", "pâtes", tags = listOf("Sans gluten"))))
        assertFalse(gf(recipe("faux", "farine de blé", tags = listOf("Sans gluten"))))
        assertEquals(listOf("farine", "chapelure"),
            DietCheck.offending(recipe("x", "farine", "sel", "chapelure"), Diet.GLUTEN_FREE).map { it.name })
    }

    @Test fun lactose() {
        assertTrue(lf(recipe("curry", "lait de coco", "poulet", "crème de coco", "beurre de cacahuètes")))
        assertTrue(lf(recipe("porridge", "lait d'avoine", "yaourt au soja", "chocolat noir")))
        assertTrue(lf(recipe("gratin", "lait sans lactose", "crème végétale", "fromage vegan")))
        assertFalse(lf(recipe("béchamel", "beurre", "farine")))
        assertFalse(lf(recipe("quiche", "Crème fraîche épaisse", "lardons")))
        assertFalse(lf(recipe("pizza", "mozzarella")))
        assertFalse(lf(recipe("double", "lait de coco, lait", "riz")))
        // Le chevreuil n'est pas du chèvre.
        assertTrue(lf(recipe("civet", "chevreuil", "vin rouge")))
        // Étiquette : le beurre (ghee, beurre doux sans lactose…) passe, pas le lait.
        assertTrue(lf(recipe("tagged", "beurre", tags = listOf("Sans lactose"))))
        assertFalse(lf(recipe("tagged2", "lait entier", tags = listOf("Sans lactose"))))
    }

    @Test fun filterCombinesDiets() {
        val idx = SearchIndex(listOf(
            recipe("soupe", "carottes", "lait de coco"),
            recipe("gratin", "pommes de terre", "crème"),
            recipe("lasagnes", "lasagnes", "lait de soja"),
            recipe("pâtes", "pâtes", "beurre"),
        ))
        fun ids(vararg d: Diet) = idx.search("", RecipeFilter(diets = d.toSet())).map { it.entry.id }.sorted()
        assertEquals(listOf("gratin", "soupe"), ids(Diet.GLUTEN_FREE))
        assertEquals(listOf("lasagnes", "soupe"), ids(Diet.LACTOSE_FREE))
        assertEquals(listOf("soupe"), ids(Diet.GLUTEN_FREE, Diet.LACTOSE_FREE))
        assertEquals(4, ids().size)
    }
}
