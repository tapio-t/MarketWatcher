package fi.marketwatcher.ui

/**
 * "What happened" notes for the History year chips: why the market was where it was at that
 * year's lowest close. Descriptive only (no buy/sell language; checked by a test). The figures
 * shown with each note (date, close, fall from the high) are computed from the data, not written here.
 */
internal val YEAR_NOTES: Map<Int, String> = mapOf(
    2008 to "The global financial crisis. US home prices fell, mortgage-linked securities collapsed and banks " +
        "stopped trusting each other. Lehman Brothers failed in September, AIG needed a government rescue and " +
        "credit froze worldwide. By November the US was in a deep recession, and fears about the car makers and " +
        "bank losses drove the index to a new low.",
    2009 to "The bottom of the financial-crisis bear market, after unemployment kept rising and investors feared " +
        "that big banks would be nationalised. The turn came within weeks: the Fed expanded its bond buying in " +
        "March, accounting rules for bank assets were eased, and the spring bank stress tests restored confidence.",
    2011 to "About a 19% fall from the April 2011 high, with the index still below its 2007 peak. A US debt-ceiling " +
        "standoff ended with S&P downgrading US government debt on 5 August, while the euro-area debt crisis " +
        "spread from Greece towards Italy and Spain. Recession fears peaked in early October, before European " +
        "rescue plans and firmer US data lifted stocks.",
    2018 to "The Fed kept raising rates and shrinking its balance sheet into a slowing economy. In October Chair " +
        "Powell said rates were \"a long way from neutral\", and the US–China trade war escalated. A government " +
        "shutdown began on 22 December and the decline peaked on Christmas Eve. The Fed's shift to patience in " +
        "January 2019 ended it.",
    2020 to "The Covid-19 pandemic shut down economies worldwide. It was the fastest fall of a third in the " +
        "index's history, about one month from the February high. On 23 March the Fed promised unlimited bond " +
        "buying, and Congress passed the CARES Act days later. The market turned before the economic damage peaked.",
    2022 to "Inflation reached 9.1% in June, the highest in four decades, and the Fed raised rates at the fastest " +
        "pace since the early 1980s. The 2-year yield surged (the app's Rate shock pattern), Russia's invasion of " +
        "Ukraine in February caused an energy shock, and the UK gilt crisis added stress in late September. The low " +
        "came a day before the 13 October inflation report, after which the market reversed.",
    2025 to "On 2 April the US announced broad \"Liberation Day\" tariffs, and the index fell about 12% in four " +
        "trading days. On 9 April a 90-day pause on most of the tariffs was announced, and the S&P 500 rose about " +
        "9.5% that day, one of its biggest daily gains since World War II.",
)

/** History info card below the year chart. Text as given by the user; the source is shown under it. */
internal const val BUBBLE_TITLE = "Ingredients of economic bubbles"
internal val BUBBLE_INGREDIENTS: List<String> = listOf(
    "abandoning of old investment wisdoms",
    "novelty of a technology or investment product",
    "a long continued economic boom",
    "a virtuous cycle fueled by returns of investments",
    "a new generation of investors",
    "growth expectations have detached from reality",
    "emergence of “100% sure” investment objects/products",
    "mainstream excitement about investing",
    "use of financial leverage (loans) hunting for returns of investments",
    "more money than ideas available",
)
internal const val BUBBLE_SOURCE =
    "Heikki Keskiväli, Considering Stocks: Hits and Misses of a Sensible Investor, December 13, 2020"
