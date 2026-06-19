function fn() {
    var webServerPort = karate.properties['webServerPort'];
    var host = karate.properties['host'];
    var protocol = karate.properties['protocol'];
    var serviceBaseUrl = protocol + '://' + host + ':' + webServerPort;
    var randomSeed = java.util.UUID.randomUUID().toString();
    return {
        'serviceBaseUrl': serviceBaseUrl,
        'randomSeed': randomSeed
    };
}
