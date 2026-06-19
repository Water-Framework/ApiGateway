function fn() {
    let webServerPort = karate.properties['webServerPort'];
    let host = karate.properties['host'];
    let protocol = karate.properties['protocol'];
    let serviceBaseUrl = protocol+"://"+host+":"+webServerPort;
    let randomSeed = java.util.UUID.randomUUID().toString();
    return {
        "serviceBaseUrl": serviceBaseUrl,
        "randomSeed": randomSeed
    }
}
