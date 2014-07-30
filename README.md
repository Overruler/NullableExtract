# NullableExtract

A quick and dirty project to read class files with @Nullable annotations and output the corresponding Java source code.

Only stub classes are generated, and only containing those methods that have @Nullable annotations. The generated source code is well-formed Java but not a replacement for the original class files.

Works at least on the annotated JDK (jdk8.jar) distributed in the [Checker Framework v1.8.3](http://types.cs.washington.edu/checker-framework/).

## Requirements

Depends on [SuperiorStreams](https://github.com/Overruler/SuperiorStreams) which can be Git cloned using [HTTPS](https://github.com/Overruler/SuperiorStreams.git) or from its GitHub site.

Built with the Eclipse IDE.

## License 

Your choice of:
- [MIT](http://opensource.org/licenses/MIT)
- [BSD](http://opensource.org/licenses/bsd-license.php)
- [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)
- [Eclipse Public License (EPL) v1.0](http://wiki.eclipse.org/EPL)
