# Clojure 1.8.0 for Android

This is Clojure 1.8.0 modified for Android.
Many thanks to [Clojure/Android](https://github.com/clojure-android) project, I could find what files
should be modified, and make use of the code.

Main changes are 
```sh
Added:   clojure.lang.DalvikDynamicClassLoader
Changed: clojure.lang.DynamicClassLoader -> clojure.lang.AbstractDynamicClassLoader
Added:   clojure.lang.DynamicClassLoader
Changed: clojure.lang.RT
Changed: clojure.core
Changed: clojure.main
```
to avoid changing of nrepl 0.6.0+

## Install
```sh
$ git clone https://github.com/myst3m/clojure
$ cd clojure
$ git submodule update
$ cd android/platform/dalvik
$ git checkout refs/tags/android-4.0.3_r1
$ cd android/platform/libcore
$ git checkout refs/tags/android-4.0.3_r1 
$ cd ../../../; mvn install -Dmaven.test.skip=true
```

Then, you can add the following in your dependencies of Leiningen/Boot project file.
```sh
[theorems/clojure "1.8.0"]
```


Notes:
 - Make sure you are on clojure-1.8.0-android branch on operations.
 - Using android 4.0.3 source
