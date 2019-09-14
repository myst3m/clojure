# Clojure 1.8.0 for Android

This is Clojure 1.8.0 modified for Android.
Many thanks to [Clojure/Android](https://github.com/clojure-android) project, I could find what files
should be modified, and make use of the code.

Main changes are 
```sh
Added:  clojure.lang.DalvikDynamicClassLoader
Change: clojure.lang.DynamicClassLoader -> clojure.lang.AbstractDynamicClassLoader
Added:  clojure.lang.DynamicClassLoader
Change: clojure.core
Change: clojure.main
```
to avoid changing of nrepl 0.6.0+

## Install
```sh
$ git clone https://github.com/myst3m/clojure
$ cd clojure
$ mvn install -Dmaven.test.skip=true
```

Then, you can add the following in your dependencies of Leiningen/Boot project file.
```sh
[theorems/clojure "1.8.0"]
```

