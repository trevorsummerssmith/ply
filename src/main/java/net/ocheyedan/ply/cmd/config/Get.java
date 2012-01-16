package net.ocheyedan.ply.cmd.config;

import net.ocheyedan.ply.Output;
import net.ocheyedan.ply.OutputExt;
import net.ocheyedan.ply.cmd.Args;
import net.ocheyedan.ply.cmd.ReliantCommand;
import net.ocheyedan.ply.cmd.Usage;
import net.ocheyedan.ply.props.Context;
import net.ocheyedan.ply.props.Prop;
import net.ocheyedan.ply.props.Props;

import java.util.*;

/**
 * User: blangel
 * Date: 12/30/11
 * Time: 2:52 PM
 *
 * A {@link net.ocheyedan.ply.cmd.Command} to print property values from the project's configuration.
 */
public class Get extends ReliantCommand {

    static class Opts {
        final Context context;
        final String propName;
        final boolean unfiltered;

        Opts(Context context, String propName, boolean unfiltered) {
            this.context = context;
            this.propName = propName;
            this.unfiltered = unfiltered;
        }
    }

    public Get(Args args) {
        super(args);
    }

    @Override public void run() {
        OutputExt.init(); // dis-regard ad-hoc props and defined properties, simply init
        super.run();
        Opts opts = parse(args);
        if (opts == null) {
            new Usage(args).run();
            return;
        }
        Map<Context, Collection<Prop>> contextMap = getProps();
        if (opts.context == null) {
            List<Context> contexts = new ArrayList<Context>(contextMap.keySet());
            Collections.sort(contexts);
            boolean printed = false;
            for (Context context : contexts) {
                if (printContext(context, contextMap.get(context), opts.propName, opts.unfiltered)) {
                    printed = true;
                }
            }
            if (printed) {
                printAppendix();
            } else {
                printNothingMessage(opts);
            }
        } else {
            if (printContext(opts.context, contextMap.get(opts.context), opts.propName, opts.unfiltered)) {
                printAppendix();
            } else {
                printNothingMessage(opts);
            }
        }
    }

    /**
     * Prints {@code contextMap} as it {@link Get} were invoked from the directory in which they were extracted.
     * @param contextMap the properties to print mapped by their {@link Context}
     * @param unfiltered true to print the unfiltered value of the property
     */
    public void print(Map<Context, Collection<Prop>> contextMap, boolean unfiltered) {
        List<Context> contexts = new ArrayList<Context>(contextMap.keySet());
        Collections.sort(contexts);
        for (Context context : contexts) {
            printContext(context, contextMap.get(context), null, unfiltered);
        }
    }

    @SuppressWarnings("fallthrough")
    protected Opts parse(Args args) {
        switch (args.args.size()) {
            case 1:
                return new Opts(null, null, false);
            case 2:
                // either propName or unfiltered
                if ("--unfiltered".equals(args.args.get(1))) {
                    return new Opts(null, null, true);
                } else {
                    return new Opts(null, args.args.get(1), false);
                }
            case 3:
                // either propName and unfiltered or context
                if ("from".equals(args.args.get(1))) {
                    return new Opts(new Context(args.args.get(2)), null, false);
                } else if ("--unfiltered".equals(args.args.get(2))) {
                    return new Opts(null, args.args.get(1), true);
                } else {
                    return null;
                }
            case 4:
                // either propName and context or context and unfiltered
                if ("from".equals(args.args.get(2))) {
                    return new Opts(new Context(args.args.get(3)), args.args.get(1), false);
                } else if ("from".equals(args.args.get(1)) && "--unfiltered".equals(args.args.get(3))) {
                    return new Opts(new Context(args.args.get(2)), null, true);
                } else {
                    return null;
                }
            case 5:
                // propName and context and unfiltered
                if ("from".equals(args.args.get(2)) && "--unfiltered".equals(args.args.get(4))) {
                    return new Opts(new Context(args.args.get(3)), args.args.get(1), true);
                }
                // fall-through
            default:
                return null;
        }
    }

    protected boolean printContext(Context context, Collection<Prop> props, String likePropName, boolean unfiltered) {
        if ((props == null) || props.isEmpty()) {
            return false;
        }
        List<Prop> properties = new ArrayList<Prop>(props);
        Collections.sort(properties);
        boolean printedHeader = false;
        boolean printedSomething = false;
        for (Prop prop : properties) {
            if ((likePropName == null) || matches(prop, likePropName)) {
                if (!printedHeader) {
                    Output.print("Properties from ^b^%s^r^", context.name);
                    printedHeader = true;
                }
                Output.print("   ^b^%s^r^ = ^cyan^%s^r^%s", prop.name, (unfiltered ? prop.unfilteredValue : prop.value), getSuffix(prop));
                printedSomething = true;
            }
        }
        return printedSomething;
    }

    protected boolean matches(Prop prop, String likePropName) {
        if (likePropName.equals(prop.name)) {
            return true;
        } else if (likePropName.startsWith("*")) {
            return prop.name.endsWith(likePropName.substring(1));
        } else if (likePropName.endsWith("*")) {
            return prop.name.startsWith(likePropName.substring(0, likePropName.length() - 1));
        }
        return false;
    }

    protected Map<Context, Collection<Prop>> getProps() {
        return Props.getLocal();
    }

    protected String getSuffix(Prop prop) {
        return "";
    }

    protected String getNothingMessageSuffix() {
        return " locally (try ^b^get-all^r^)";
    }

    protected void printAppendix() { }

    protected void printNothingMessage(Opts opts) {
        if (opts.context == null) {
            if (opts.propName == null) {
                Output.print("No properties in any context found %s.", getNothingMessageSuffix());
            } else {
                Output.print("No property like ^b^%s^r^ found in any context%s.", opts.propName, getNothingMessageSuffix());
            }
        } else if (opts.propName == null) {
            Output.print("No context ^b^%s^r^ found%s.", opts.context, getNothingMessageSuffix());
        } else {
            Output.print("No property like ^b^%s^r^ found in context ^b^%s^r^%s.", opts.propName, opts.context, getNothingMessageSuffix());
        }
    }

}
