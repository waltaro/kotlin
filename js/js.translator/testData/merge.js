var stdlib = module.exports;

(function () {
    Object.getOwnPropertyNames(stdlib).forEach(function(propertyName) {
        Kotlin[propertyName] = stdlib[propertyName];
    });
})();
