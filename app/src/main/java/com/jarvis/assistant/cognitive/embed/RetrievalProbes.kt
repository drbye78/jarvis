package com.jarvis.assistant.cognitive.embed

/**
 * COGNITIVE_PLAN Phase 3 (§12.4-3): the on-device benchmark probe set —
 * STATIC SYNTHETIC RU retrieval probes compiled into the app.
 *
 * Why in code (not fixtures/assets): the same probe set serves the
 * Settings «Проверить качество поиска» action AND the CI eval, with zero
 * asset-audit surface and zero drift between the two runs. The probes are
 * synthetic — «пользователь»-named facts that no user ever wrote — so the
 * CLOUD branch of the benchmark sends NO user data (§9.2), which is why
 * the benchmark needs no privacy dialog while vector backfill does.
 *
 * Probe design mirrors the §10.2 gate: each probe is a small memory store
 * (target fact + realistic distractors) and a query phrased the way a user
 * actually asks — paraphrases, synonyms (начальник/руководитель), light
 * morphology, partial value mentions — plus distractors that punish a
 * channel that just echoes token overlap.
 */
object RetrievalProbes {

    private fun f(id: String, category: String, predicate: String, value: String) =
        EmbedderBenchmark.FixtureFact(factId = id, category = category, predicate = predicate, value = value)

    val fixtures: List<EmbedderBenchmark.Fixture> by lazy {
        buildList {
            add(
                EmbedderBenchmark.Fixture(
                    id = "p01",
                    facts = listOf(
                        f("f01a", "RELATION", "boss", "Иванов Сергей Петрович"),
                        f("f01b", "PREFERENCE", "likes", "джаз"),
                        f("f01c", "IDENTITY", "lives_in", "Казань"),
                        f("f01d", "PREFERENCE", "favorite", "фильмы Тарковского"),
                    ),
                    query = "кто мой начальник?",
                    expectedFactIds = listOf("f01a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p02",
                    facts = listOf(
                        f("f02a", "PREFERENCE", "likes", "гулять по набережной вечером"),
                        f("f02b", "ROUTINE", "routine", "утренняя пробежка в парке"),
                        f("f02c", "PREFERENCE", "dislikes", "шумные компании"),
                        f("f02d", "POSSESSION", "owns", "велосипед"),
                    ),
                    query = "что я люблю делать по вечерам?",
                    expectedFactIds = listOf("f02a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p03",
                    facts = listOf(
                        f("f03a", "RELATION", "spouse", "Маша"),
                        f("f03b", "RELATION", "child", "дочь Соня"),
                        f("f03c", "RELATION", "colleague", "Андрей из отдела тестирования"),
                        f("f03d", "IDENTITY", "name", "Алексей"),
                    ),
                    query = "как зовут мою жену?",
                    expectedFactIds = listOf("f03a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p04",
                    facts = listOf(
                        f("f04a", "PREFERENCE", "favorite", "слушаю аудиокниги по дороге на работу"),
                        f("f04b", "PREFERENCE", "likes", "кофе по утрам"),
                        f("f04c", "GOAL", "goal", "выучить английский"),
                        f("f04d", "ROUTINE", "routine", "читать новости за завтраком"),
                    ),
                    query = "что я слушаю в машине?",
                    expectedFactIds = listOf("f04a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p05",
                    facts = listOf(
                        f("f05a", "RELATION", "works_at", "Яндекс"),
                        f("f05b", "RELATION", "works_as", "разработчик"),
                        f("f05c", "IDENTITY", "age", "34 года"),
                        f("f05d", "PREFERENCE", "likes", "настольные игры"),
                    ),
                    query = "где я работаю?",
                    expectedFactIds = listOf("f05a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p06",
                    facts = listOf(
                        f("f06a", "POSSESSION", "owns", "чёрная куртка уготована в химчистку"),
                        f("f06b", "ROUTINE", "routine", "тренировка по вторникам и четвергам"),
                        f("f06c", "PREFERENCE", "likes", "готовить пасту"),
                        f("f06d", "HEALTH", "health", "аллергия на пыль"),
                    ),
                    query = "что за куртка была в химчистке?",
                    expectedFactIds = listOf("f06a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p07",
                    facts = listOf(
                        f("f07a", "RELATION", "parent", "папа Виктор"),
                        f("f07b", "RELATION", "friend", "друг детства Костя"),
                        f("f07c", "IDENTITY", "birthday", "12 апреля"),
                        f("f07d", "PREFERENCE", "likes", "рыбалка"),
                    ),
                    query = "как зовут моего отца?",
                    expectedFactIds = listOf("f07a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p08",
                    facts = listOf(
                        f("f08a", "PREFERENCE", "favorite", "любимый фильм — Сталкер"),
                        f("f08b", "PREFERENCE", "likes", "сериалы про космос"),
                        f("f08c", "PREFERENCE", "dislikes", "ужасы"),
                        f("f08d", "GOAL", "goal", "собрать домашний кинотеатр"),
                    ),
                    query = "какой у меня любимый фильм?",
                    expectedFactIds = listOf("f08a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p09",
                    facts = listOf(
                        f("f09a", "RELATION", "pet", "кот Барсик"),
                        f("f09b", "RELATION", "spouse", "жена Маша"),
                        f("f09c", "POSSESSION", "owns", "аквариум"),
                        f("f09d", "ROUTINE", "routine", "кормить кота в семь утра"),
                    ),
                    query = "кто у нас дома, кот или собака?",
                    expectedFactIds = listOf("f09a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p10",
                    facts = listOf(
                        f("f10a", "IDENTITY", "lives_in", "живу в Питере на Петроградке"),
                        f("f10b", "IDENTITY", "name", "Алексей"),
                        f("f10c", "PREFERENCE", "likes", "прогулки по центру"),
                        f("f10d", "RELATION", "colleague", "коллега Оля"),
                    ),
                    query = "в каком городе я живу?",
                    expectedFactIds = listOf("f10a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p11",
                    facts = listOf(
                        f("f11a", "ROUTINE", "routine", "созвон с командой в десять утра"),
                        f("f11b", "ROUTINE", "routine", "спортзал по вечерам"),
                        f("f11c", "PREFERENCE", "likes", "тихие утра"),
                        f("f11d", "GOAL", "goal", "марафон осенью"),
                    ),
                    query = "во сколько у меня созвон?",
                    expectedFactIds = listOf("f11a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p12",
                    facts = listOf(
                        f("f12a", "RELATION", "studies_at", "курсы испанского в Language Hero"),
                        f("f12b", "GOAL", "goal", "переезд в Испанию"),
                        f("f12c", "PREFERENCE", "likes", "испанская кухня"),
                        f("f12d", "IDENTITY", "age", "34 года"),
                    ),
                    query = "где я учу испанский?",
                    expectedFactIds = listOf("f12a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p13",
                    facts = listOf(
                        f("f13a", "PREFERENCE", "likes", "печь хлеб по выходным"),
                        f("f13b", "PREFERENCE", "dislikes", "магазинный хлеб"),
                        f("f13c", "POSSESSION", "owns", "хлебопечка"),
                        f("f13d", "ROUTINE", "routine", "субботняя уборка"),
                    ),
                    query = "что я пеку на выходных?",
                    expectedFactIds = listOf("f13a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p14",
                    facts = listOf(
                        f("f14a", "RELATION", "friend", "Света из университета"),
                        f("f14b", "RELATION", "boss", "начальница Ольга Игоревна"),
                        f("f14c", "PREFERENCE", "likes", "встречи с друзьями"),
                        f("f14d", "IDENTITY", "lives_in", "Казань"),
                    ),
                    query = "кто моя подруга?",
                    expectedFactIds = listOf("f14a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p15",
                    facts = listOf(
                        f("f15a", "HEALTH", "health", "давление скачет по утрам"),
                        f("f15b", "PREFERENCE", "likes", "чай вместо кофе"),
                        f("f15c", "ROUTINE", "routine", "прогулка перед сном"),
                        f("f15d", "GOAL", "goal", "больше спать"),
                    ),
                    query = "что я говорил про давление?",
                    expectedFactIds = listOf("f15a"),
                ),
            )
            add(
                EmbedderBenchmark.Fixture(
                    id = "p16",
                    facts = listOf(
                        f("f16a", "POSSESSION", "owns", "электросамокат"),
                        f("f16b", "POSSESSION", "owns", "ноутбук для работы"),
                        f("f16c", "PREFERENCE", "likes", "кататься по набережной"),
                        f("f16d", "ROUTINE", "routine", "заряжать самокат по пятницам"),
                    ),
                    query = "у меня есть самокат?",
                    expectedFactIds = listOf("f16a"),
                ),
            )
        }
    }
}
