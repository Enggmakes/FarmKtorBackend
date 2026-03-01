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
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
            
            // 1. Visit the home page first to get a session cookie
            val homeResponse = Jsoup.connect("https://www.agriwelfare.gov.in/")
                .userAgent(userAgent)
                .timeout(15000)
                .execute()
            val cookies = homeResponse.cookies()

            // 2. Connect to the government website with extremely robust stealth headers and cookies
            val doc = Jsoup.connect("https://www.agriwelfare.gov.in/en/Major")
                .userAgent(userAgent)
                .cookies(cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.agriwelfare.gov.in/")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-User", "?1")
                .header("Connection", "keep-alive")
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
