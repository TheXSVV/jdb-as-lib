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
public class Main {

    private static final Logger LOGGER = LogManager.getLogger(Main.class);

    @SneakyThrows
    public static void main(String[] args) {
        JDB jdb = new JDB(true).start(); // Start the Agent server

        Scanner scanner = new Scanner(System.in);
        if (scanner.next() != null) { // Wait for a line in the console input, this is necessary, so you have time to run the jar file with the agent and find out the PIDs
            jdb.getAviablePids().forEach((pid, cmd) -> {
                LOGGER.info("Aviable PID: {} - {}", pid, cmd); // Printing active JVMs (pid and command line)
            });
        }
        jdb.attach(scanner.nextInt()) // Attaching to the PID from console input
                .setContext("Main", matches -> {
                    LOGGER.info("Multiple matches found");
                    for (int i = 0; i < matches.size(); i++)
                        LOGGER.info(" {}: {}", i, matches.get(i).name());

                    LOGGER.info("Selecting: {}", 0);
                    return 0;
                }) // Setting a context to Main class with 0 match
                .setBreakpoint(5); // Set the breakpoint at 5 line of Main class
        scanner.next(); // Wait for a line in the console input, this is necessary, so you have time for waiting a breakpoint
        jdb.returns().forEach((methodName, returns) -> {
            LOGGER.info("Returns of the {}: {}", methodName.name(), returns.type().name()); // Printing returns of the methods
        });
    }
}
```
