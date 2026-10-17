// Rejuv run driver: boots the real Spring context (real beans, real PostgreSQL, no Discord login)
// and drives it. Launched by driver.sh as a single-file source program on the test classpath.
//
//   list                          print every SlashCommand bean as Discord would receive it
//   cmd <name> [opt=value ...]    route a fake slash event through the real CommandRouter, print the reply calls
//   eval '<java>'                 run Java against the live context; helpers: ctx, bean(Class)
//
// Discord is never contacted: bot.token is forced empty, so the JDA bean is not created.

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import sntiago05.rejuv.RejuvApplication;
import sntiago05.rejuv.commands.CommandRouter;
import sntiago05.rejuv.commands.SlashCommand;

public class Driver {

    static final List<String> transcript = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: Driver list | cmd <name> [opt=value ...] [--user=<id>] | eval '<java>'");
            System.exit(2);
        }
        int exit = 0;
        try (ConfigurableApplicationContext ctx = boot()) {
            switch (args[0]) {
                case "list" -> list(ctx);
                case "cmd" -> exit = cmd(ctx, args);
                case "eval" -> exit = eval(ctx, args[1]);
                default -> {
                    System.err.println("unknown mode: " + args[0]);
                    exit = 2;
                }
            }
        }
        System.exit(exit);
    }

    static ConfigurableApplicationContext boot() {
        SpringApplication app = new SpringApplication(RejuvApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        return app.run(
                "--bot.token=",
                "--bot.dev-guild-id=",
                "--logging.level.root=WARN",
                "--logging.level.sntiago05=INFO");
    }

    static void list(ConfigurableApplicationContext ctx) {
        for (SlashCommand command : ctx.getBeansOfType(SlashCommand.class).values()) {
            SlashCommandData data = command.data();
            System.out.println("/" + data.getName() + " - " + data.getDescription());
            for (OptionData option : data.getOptions()) {
                System.out.println("    " + option.getName() + " (" + option.getType()
                        + (option.isRequired() ? ", required" : "") + ") - " + option.getDescription());
            }
        }
    }

    static int cmd(ConfigurableApplicationContext ctx, String[] args) {
        String name = args[1];
        String userId = "100000000000000001";
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--user=")) {
                userId = args[i].substring("--user=".length());
            } else {
                String[] kv = args[i].split("=", 2);
                options.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }

        SlashCommandInteractionEvent event = fakeEvent(name, userId, options);
        ctx.getBean(CommandRouter.class).onSlashCommandInteraction(event);

        System.out.println("--- reply calls (" + transcript.size() + ") ---");
        transcript.forEach(System.out::println);
        boolean replied = transcript.stream().anyMatch(line -> line.contains(".queue(") || line.contains(".complete("));
        if (!replied) {
            System.out.println("WARNING: no queue()/complete() call - Discord would never see a reply");
            return 1;
        }
        return 0;
    }

    static SlashCommandInteractionEvent fakeEvent(String name, String userId, Map<String, String> options) {
        User user = Mockito.mock(User.class);
        Mockito.when(user.getId()).thenReturn(userId);
        Mockito.when(user.getIdLong()).thenReturn(Long.parseLong(userId));
        Mockito.when(user.getName()).thenReturn("driver-user");

        JDA jda = Mockito.mock(JDA.class);
        Mockito.when(jda.getGatewayPing()).thenReturn(42L);

        Map<String, OptionMapping> mappings = new LinkedHashMap<>();
        options.forEach((key, value) -> mappings.put(key, option(key, value)));

        Recorder recorder = new Recorder("event") {
            @Override
            public Object answer(InvocationOnMock inv) throws Throwable {
                Method m = inv.getMethod();
                Object[] a = inv.getArguments();
                return switch (m.getName()) {
                    case "getName", "getFullCommandName" -> name;
                    case "getUser" -> user;
                    case "getJDA" -> jda;
                    case "getOptions" -> List.copyOf(mappings.values());
                    case "getOption" -> {
                        OptionMapping mapping = mappings.get((String) a[0]);
                        if (a.length == 1) {
                            yield mapping;
                        }
                        // getOption(name, resolver) / getOption(name, fallback, resolver): use JDA's own logic
                        yield inv.callRealMethod();
                    }
                    default -> super.answer(inv);
                };
            }
        };
        return Mockito.mock(SlashCommandInteractionEvent.class, recorder);
    }

    static OptionMapping option(String key, String value) {
        OptionMapping mapping = Mockito.mock(OptionMapping.class);
        Mockito.when(mapping.getName()).thenReturn(key);
        Mockito.when(mapping.getType()).thenReturn(OptionType.STRING);
        Mockito.when(mapping.getAsString()).thenReturn(value);
        Mockito.when(mapping.getAsBoolean()).thenAnswer(i -> Boolean.parseBoolean(value));
        Mockito.when(mapping.getAsLong()).thenAnswer(i -> Long.parseLong(value));
        Mockito.when(mapping.getAsInt()).thenAnswer(i -> Integer.parseInt(value));
        Mockito.when(mapping.getAsDouble()).thenAnswer(i -> Double.parseDouble(value));
        return mapping;
    }

    /**
     * Records every non-getter call as one transcript line per fluent chain, for example
     * {@code event.reply("x").setEphemeral(true).queue()}. Fluent calls return the mock itself;
     * other non-final return types get a child recorder that keeps extending the same line.
     */
    static class Recorder implements Answer<Object> {

        String chain;
        int line;

        Recorder(String chain) {
            this(chain, -1);
        }

        Recorder(String chain, int line) {
            this.chain = chain;
            this.line = line;
        }

        @Override
        public Object answer(InvocationOnMock inv) throws Throwable {
            Method m = inv.getMethod();
            String n = m.getName();
            if (n.equals("toString")) {
                return chain;
            }
            if (n.equals("hashCode") || n.equals("equals")) {
                return Mockito.RETURNS_DEFAULTS.answer(inv);
            }
            boolean getter = n.startsWith("get") || n.startsWith("is");
            String next = chain + "." + n + "(" + format(inv.getArguments()) + ")";
            int nextLine = -1;
            if (!getter) {
                if (line < 0) {
                    transcript.add(next);
                    nextLine = transcript.size() - 1;
                } else {
                    transcript.set(line, next);
                    nextLine = line;
                }
            }
            Class<?> rt = m.getReturnType();
            if (rt.isInstance(inv.getMock())) {
                if (!getter) {
                    chain = next;
                    line = nextLine;
                }
                return inv.getMock();
            }
            // Any other call ends this receiver's chain; a later call on it starts a new line.
            line = -1;
            if (rt == void.class) {
                return null;
            }
            if (!rt.isPrimitive() && !Modifier.isFinal(rt.getModifiers()) && rt != Object.class) {
                return Mockito.mock(rt, new Recorder(next, nextLine));
            }
            return Mockito.RETURNS_DEFAULTS.answer(inv);
        }
    }

    static String format(Object[] args) {
        List<String> parts = new ArrayList<>();
        for (Object arg : args) {
            parts.add(format(arg));
        }
        return String.join(", ", parts);
    }

    static String format(Object arg) {
        if (arg instanceof String s) {
            return "\"" + s.replace("\n", "\\n") + "\"";
        }
        if (arg instanceof MessageEmbed embed) {
            return "embed" + embed.toData();
        }
        if (arg instanceof MessageCreateData data) {
            return "message{content=" + format(data.getContent()) + ", embeds=" + format(data.getEmbeds()) + "}";
        }
        if (arg instanceof MessageEditData data) {
            return "edit{content=" + format(data.getContent()) + ", embeds=" + format(data.getEmbeds()) + "}";
        }
        if (arg instanceof Collection<?> c) {
            return c.stream().map(Driver::format).collect(Collectors.joining(", ", "[", "]"));
        }
        if (arg instanceof Object[] array) {
            return "[" + format(array) + "]";
        }
        return String.valueOf(arg);
    }

    // eval: compile a throwaway class against the running classpath and call it with the live context.
    static int eval(ConfigurableApplicationContext ctx, String code) throws Exception {
        String body = code.contains("return ") || code.trim().endsWith(";") ? code : "return (" + code + ");";
        if (!body.contains("return ")) {
            body = body + "\nreturn null;";
        }
        StringBuilder imports = new StringBuilder("import java.util.*;\nimport java.time.*;\n");
        for (String pkg : List.of("", ".commands", ".services", ".clients", ".clients.dto", ".repositories", ".domain", ".config")) {
            String name = "sntiago05.rejuv" + pkg;
            if (Driver.class.getClassLoader().getResource(name.replace('.', '/')) != null) {
                imports.append("import ").append(name).append(".*;\n");
            }
        }
        String source = imports
                + "public class DriverEval {\n"
                + "  static org.springframework.context.ApplicationContext ctx;\n"
                + "  static <T> T bean(Class<T> type) { return ctx.getBean(type); }\n"
                + "  public static Object run(org.springframework.context.ApplicationContext c) throws Exception {\n"
                + "    ctx = c;\n" + body + "\n  }\n}\n";

        Path dir = Files.createTempDirectory("rejuv-eval");
        Path file = dir.resolve("DriverEval.java");
        Files.writeString(file, source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", dir.toString(), file.toString());
        if (rc != 0) {
            System.err.println("eval: compilation failed; generated source:\n" + source);
            return 1;
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, Driver.class.getClassLoader())) {
            Object result = loader.loadClass("DriverEval")
                    .getMethod("run", org.springframework.context.ApplicationContext.class)
                    .invoke(null, ctx);
            System.out.println("=> " + format(result));
        } catch (java.lang.reflect.InvocationTargetException ex) {
            System.out.println("=> threw:");
            ex.getCause().printStackTrace(System.out);
            return 1;
        }
        return 0;
    }
}
