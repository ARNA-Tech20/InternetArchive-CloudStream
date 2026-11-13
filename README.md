# Internet Archive for CloudStream

A CloudStream plugin that streams **public-domain and Creative Commons** films, classic TV, silent films, film noir, cartoons and vintage educational films from the [Internet Archive](https://archive.org). English-language items only.

Only items whose uploader declared a Public Domain or Creative Commons licence are listed.

## Install
In CloudStream: **Settings → Extensions → Add repository**, then paste:

```
https://raw.githubusercontent.com/ARNA-Tech20/InternetArchive-CloudStream/builds/repo.json
```

## Build locally
```
./gradlew InternetArchiveProvider:make makePluginsJson
```
Every push to `master` is built by GitHub Actions and published to the `builds` branch.
