JDB
===

Advanced CS Projects class project.

JDB is a Java for Java programs similiar in function to C's GDB. I have implemented a lot fewer features, as I felt that it was complete halfway through the year. I didn't end up following up on a lot of "nice-to-haves" in the planning document, which can be found [here](https://github.com/AgentTroll/jdb/blob/master/jdb.md).

I gave a presentation to the staff about my project, the slides and annotated transcript can be found [on my blog](https://agenttroll.github.io/blog/2018/05/11/jdb-but-not-that-one.html).

# Target VM

```shell
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n -javaagent:topics-agent-1.0-SNAPSHOT.jar ...
```

# Demo usage:

```java
Will be soon...
```

### Original Developer: caojohnny
### My Changes:
* Updated **lombok** to **1.18.28**
* Updated **guava** to **30.0-jre**
* Added **log4** logger
* Rewritten **Platform**
* Added **JDB** as main class of library
* Removed wtf from **Enter**:
```java
if (idx == 0) {
    return null; // WTF Bro
}
```