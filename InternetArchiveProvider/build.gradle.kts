// Use an integer for version numbers
version = 1

cloudstream {
    description = "Public-domain and Creative Commons films, classic TV, silent films and cartoons from the Internet Archive (archive.org). English only."
    authors = listOf("Ricky Azwan")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Cartoon", "Documentary")

    language = "en"

    iconUrl = "https://archive.org/images/glogo.jpg"
}
