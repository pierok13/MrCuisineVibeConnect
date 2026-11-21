package app.toque.core.recipes

import app.toque.core.i18n.trKey

/**
 * Régimes « sans » reconnus par la liste de recettes.
 *
 * Les recettes Lidl n'ont pas toutes une étiquette : Toque lit donc aussi les ingrédients. Une
 * recette étiquetée « Sans gluten » (ou « Sans lactose ») n'est écartée que par un ingrédient qui
 * en contient à coup sûr (farine de blé, lait de vache…) ; une recette sans étiquette l'est aussi
 * par un ingrédient qui en contient d'ordinaire (pâtes, pain, beurre, fromage…), sauf mention
 * « sans gluten », « sans lactose », « végétal »… C'est une aide au tri, pas une garantie :
 * bouillons, épices et traces ne figurent pas dans les ingrédients.
 */
enum class Diet(val key: String, private val fr: String) {
    GLUTEN_FREE("gluten", trKey("Sans gluten")),
    LACTOSE_FREE("lactose", trKey("Sans lactose"));

    val label: String get() = app.toque.core.i18n.tr(fr)

    companion object {
        fun of(key: String?): Diet? = values().firstOrNull { it.key == key?.trim() }
    }
}

object DietCheck {
    private class Rules(
        /** Étiquettes de la recette qui annoncent le régime. */
        tags: List<String>,
        /** Mentions qui rendent tout l'ingrédient compatible (« pain sans gluten »). */
        safe: List<String>,
        /** Expressions compatibles retirées avant la recherche (« lait de coco »). */
        exceptions: List<String>,
        /** Contient à coup sûr : écarte même une recette étiquetée. */
        sure: List<String>,
        /** Contient d'ordinaire : écarte une recette sans étiquette. */
        likely: List<String>,
    ) {
        val tags = tags.map(::key)
        val safe = safe.map(::key)
        val exceptions = exceptions.map(::key).sortedByDescending { it.length }
        val sure = sure.map(::key)
        val likely = likely.map(::key)
    }

    /** Forme de comparaison : minuscules, sans accents, pluriels simples, mots séparés par une espace. */
    private fun key(s: String): String = TextNorm.tokens(s, keepStopWords = true).joinToString(" ")

    private val GLUTEN = Rules(
        tags = listOf("sans gluten", "gluten free", "glutenfrei", "senza glutine", "sin gluten", "glutenvrij"),
        safe = listOf("sans gluten", "gluten free", "glutenfrei"),
        exceptions = listOf(
            "farine de ble noir", "ble noir", "sarrasin",
            "farine de riz", "farine de mais", "farine de sarrasin", "farine de chataigne", "farine de pois chiche",
            "farine de coco", "farine d amande", "farine de noisette", "farine de lupin", "farine de quinoa",
            "farine de millet", "farine de teff", "farine de manioc", "farine de lentille", "farine de souchet",
            "farine de banane", "farine de pomme de terre",
            "semoule de mais", "semoule de riz", "polenta",
            "nouille de riz", "vermicelle de riz", "vermicelle chinois", "galette de riz", "feuille de riz",
            "pate de riz", "pate d amande", "pate de datte", "pate de curry", "pate de sesame", "pate de cacahuete",
            "pate de piment", "pate de fruit", "pate de coing", "pate de tamarin", "pate de tomate",
            "tortilla de mais", "pain de viande",
            "sauce soja sans gluten", "tamari",
        ),
        sure = listOf(
            "ble", "froment", "seigle", "orge", "epeautre", "petit epeautre", "kamut", "triticale", "seitan",
            "farine de ble", "farine t45", "farine t55", "farine t65", "farine t80", "farine t110",
            "chapelure", "panko", "couscous", "boulgour", "boulghour", "bulgur", "ebly", "malt", "biere",
            "semoule de ble", "son de ble", "germe de ble", "gluten",
        ),
        likely = listOf(
            "farine", "semoule", "pate", "pain", "pain de mie", "baguette", "brioche", "croissant", "biscotte",
            "biscuit", "speculoos", "boudoir", "gaufrette", "crouton", "cracker", "chips de pain",
            "spaghetti", "tagliatelle", "linguine", "fettuccine", "penne", "fusilli", "farfalle", "macaroni",
            "coquillette", "torsade", "rigatoni", "orzo", "lasagne", "cannelloni", "ravioli", "tortellini",
            "gnocchi", "nouille", "vermicelle", "udon", "ramen", "tortilla", "wrap", "pita", "naan", "pizza",
            "avoine", "flocon d avoine", "son d avoine",
            "sauce soja", "sauce worcestershire", "sauce teriyaki", "sauce hoisin",
        ),
    )

    private val LACTOSE = Rules(
        tags = listOf("sans lactose", "lactose free", "laktosefrei", "senza lattosio", "sin lactosa", "lactosevrij",
            "vegan", "vegane", "vegetalien", "vegetalienne"),
        safe = listOf("sans lactose", "lactose free", "laktosefrei", "delactose", "vegan", "vegane",
            "vegetal", "vegetale", "vegetalien", "vegetalienne"),
        exceptions = listOf(
            "lait de coco", "lait d amande", "lait de soja", "lait d avoine", "lait de riz", "lait de noisette",
            "lait de cajou", "lait d epeautre", "lait de chanvre",
            "creme de coco", "creme de soja", "creme d avoine", "creme de riz", "creme d amande",
            "creme de cajou", "creme de marron", "creme de balsamique", "creme de vinaigre balsamique",
            "creme de cassis", "creme de whisky",
            "beurre de cacahuete", "beurre d arachide", "beurre d amande", "beurre de cajou", "beurre de noisette",
            "beurre de coco", "beurre de karite", "beurre de cacao",
            "yaourt de soja", "yaourt au soja", "yaourt de coco", "yaourt au lait de coco",
            "noix de coco", "chocolat noir", "fromage de soja", "tofu",
        ),
        sure = listOf(
            "lait", "lait entier", "lait demi ecreme", "lait ecreme", "lait concentre", "lait en poudre",
            "creme fraiche", "creme liquide", "creme epaisse", "creme entiere", "creme fleurette",
            "fromage blanc", "faisselle", "petit suisse", "yaourt", "yoghourt", "yogourt", "skyr", "kefir",
            "babeurre", "lait ribot", "petit lait", "lactoserum", "mascarpone", "ricotta", "cottage",
            "quark", "fromage frais", "chocolat au lait", "chocolat blanc", "dulce de leche", "lactose",
            "bechamel", "chantilly",
        ),
        likely = listOf(
            "beurre", "creme", "ghee", "beurre clarifie", "fromage", "parmesan", "parmigiano", "grana padano",
            "pecorino", "mozzarella", "mozarella", "burrata", "emmental", "gruyere", "comte", "beaufort",
            "feta", "cheddar", "chevre", "brie", "camembert", "roquefort", "gorgonzola", "reblochon",
            "raclette", "tomme", "gouda", "edam", "manchego", "halloumi", "munster", "morbier", "fourme",
            "saint nectaire", "cancoillotte", "philadelphia", "boursin", "saint moret", "carre frais",
            "fromage rape", "fromage a tartiner", "pate a tartiner",
        ),
    )

    private fun rules(d: Diet) = when (d) {
        Diet.GLUTEN_FREE -> GLUTEN
        Diet.LACTOSE_FREE -> LACTOSE
    }

    /** La recette porte une étiquette du régime (« Sans gluten »…). */
    fun tagged(recipe: Recipe, diet: Diet): Boolean {
        val r = rules(diet)
        return (recipe.tags + recipe.category).any { key(it) in r.tags }
    }

    /** Ingrédients qui écartent la recette du régime (vide : la recette convient). */
    fun offending(recipe: Recipe, diet: Diet): List<Ingredient> {
        val r = rules(diet)
        val tagged = tagged(recipe, diet)
        return recipe.allIngredients.filter { i ->
            val k = key(i.name + " " + i.note)
            contains(k, r, if (tagged) r.sure else r.sure + r.likely)
        }
    }

    fun fits(recipe: Recipe, diet: Diet): Boolean = offending(recipe, diet).isEmpty()

    /** Régimes auxquels la recette convient. */
    fun dietsOf(recipe: Recipe): Set<Diet> = Diet.values().filterTo(LinkedHashSet()) { fits(recipe, it) }

    private fun contains(k: String, r: Rules, words: List<String>): Boolean {
        var text = " $k "
        if (r.safe.any { text.contains(" $it ") }) return false
        // Deux espaces : une expression répétée (« lait de coco, lait de coco ») est retirée en entier.
        for (e in r.exceptions) while (text.contains(" $e ")) text = text.replace(" $e ", "  ")
        return words.any { text.contains(" $it ") }
    }
}
