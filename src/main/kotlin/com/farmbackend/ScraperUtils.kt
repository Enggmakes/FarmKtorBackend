package com.farmbackend

import org.jsoup.Jsoup
import java.util.UUID

data class Scheme(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val link: String,
    val date: String = "",
    val timestamp: Long = 0L
)

object ScraperUtils {
    fun fetchSchemes(): List<Scheme> {
        val schemes = mutableListOf<Scheme>()
        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.162 Mobile Safari/537.36"
            
            // Final attempt: Direct call to the target but with Google as the Referer
            val doc = Jsoup.connect("https://agriwelfare.gov.in/en/Major")
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Referer", "https://www.google.com/")
                .header("DNT", "1")
                .header("Upgrade-Insecure-Requests", "1")
                .timeout(30000)
                .get()
            
            // Select all rows from the table in the main content area
            val rows = doc.select("table tbody tr, table tr")

            val addedTitles = mutableSetOf<String>()

            val dateFormat = java.text.SimpleDateFormat("dd-MM-yyyy")
            
            for (row in rows) {
                val cols = row.select("td")
                
                // Typical table: [0] S.No., [1] Title, [2] Date, [3] Download/Link
                if (cols.size >= 4) {
                    val title = cols[1].text().trim()
                    val dateStr = cols[2].text().trim()
                    
                    // The link is usually in the last column
                    val linkElement = cols.last()?.select("a[href]")?.first()
                    var url = linkElement?.attr("href")?.trim()

                    if (title.isNotEmpty() && title.length > 3 && url != null && url.isNotEmpty()) {
                        
                        // Resolve relative URLs
                        if (url.startsWith("/")) {
                            url = "https://agriwelfare.gov.in$url"
                        }
                        
                        // Prevent duplicates and header rows
                        if (!addedTitles.contains(title) && !title.lowercase().equals("title")) {
                            var timestamp = 0L
                            try {
                                timestamp = dateFormat.parse(dateStr)?.time ?: 0L
                            } catch (e: Exception) {
                                // Ignore parse errors
                            }
                            schemes.add(Scheme(title = title, link = url, date = dateStr, timestamp = timestamp))
                            addedTitles.add(title)
                        }
                    }
                }
            }
            
            // Sort by Date Descending
            schemes.sortByDescending { it.timestamp }
            
            println("Scraped ${schemes.size} potential schemes.")
            schemes.forEach { println("Scheme found: ${it.title} -> ${it.link}") }
            
        } catch (e: Exception) {
            println("Error scraping schemes: ${e.message}")
            e.printStackTrace()
        }
        return schemes
    }
}
