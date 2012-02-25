package net.ocheyedan.ply.cmd.config;

import net.ocheyedan.ply.Output;
import net.ocheyedan.ply.PlyUtil;
import net.ocheyedan.ply.cmd.Args;
import net.ocheyedan.ply.cmd.Usage;
import net.ocheyedan.ply.props.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static net.ocheyedan.ply.props.PropFile.Prop;

/**
 * User: blangel
 * Date: 12/30/11
 * Time: 2:52 PM
 *
 * A {@link net.ocheyedan.ply.cmd.Command} to print property values from the project's configuration.
 */
public class Get extends Config {

    static class Opts {
        final Scope scope;
        final Context context;
        final String propName;
        final boolean unfiltered;

        Opts(Scope scope, Context context, String propName, boolean unfiltered) {
            this.scope = scope;
            this.context = context;
            this.propName = propName;
            this.unfiltered = unfiltered;
        }
    }

    public Get(Args args) {
        super(args);
    }

    @Override protected void runAfterAssumptionsCheck() {
        Opts opts = parse(args);
        if (opts == null) {
            new Usage(args).run();
            return;
        }
        print(PlyUtil.LOCAL_CONFIG_DIR, opts.context, opts.scope, opts.propName, opts.unfiltered);
    }

    /**
     * Prints properties (those which pass {@link #accept(net.ocheyedan.ply.props.PropFile.Prop)}) from directory
     * {@code configurationDirectory}.
     * @param configurationDirectory from which to get properties to print
     * @param context if null, all contexts are printed, otherwise only properties matching this context are printed
     * @param scope if null, the {@link Scope#Default} properties are printed otherwise the properties from the scope are printed
     * @param propName if null, all properties are printed, otherwise, only those properties matching this name are printed
     * @param unfiltered if true, the unfiltered properties values are printed, otherwise the filtered values are printed.
     */
    public void print(File configurationDirectory, Context context, Scope scope, String propName, boolean unfiltered) {
        Map<Context, PropFileChain> contextMap = Props.get(scope, configurationDirectory);
        if (context == null) {
            List<Context> contexts = new ArrayList<Context>(contextMap.keySet());
            Collections.sort(contexts);
            boolean printed = false;
            for (Context currentContext : contexts) {
                if (printContext(currentContext, scope, contextMap.get(currentContext).props(), propName, unfiltered)) {
                    printed = true;
                }
            }
            if (printed) {
                printAppendix(scope);
            } else {
                printNothingMessage(context, propName);
            }
        } else {
            if (contextMap.containsKey(context)
                    && printContext(context, scope, contextMap.get(context).props(), propName, unfiltered)) {
                printAppendix(scope);
            } else {
                printNothingMessage(context, propName);
            }
        }
    }

    @SuppressWarnings("fallthrough")
    protected Opts parse(Args args) {
        Scope scope = Scope.Default;
        int scopeIndex = args.args.get(0).indexOf(":");
        if (scopeIndex != -1) {
            scope = Scope.named(args.args.get(0).substring(0, scopeIndex));
        }
        switch (args.args.size()) {
            case 1:
                return new Opts(scope, null, null, false);
            case 2:
                // either propName or unfiltered
                if ("--unfiltered".equals(args.args.get(1))) {
                    return new Opts(scope, null, null, true);
                } else {
                    return new Opts(scope, null, args.args.get(1), false);
                }
            case 3:
                // either propName and unfiltered or context
                if ("from".equals(args.args.get(1))) {
                    return new Opts(scope, new Context(args.args.get(2)), null, false);
                } else if ("--unfiltered".equals(args.args.get(2))) {
                    return new Opts(scope, null, args.args.get(1), true);
                } else {
                    return null;
                }
            case 4:
                // either propName and context or context and unfiltered
                if ("from".equals(args.args.get(2))) {
                    return new Opts(scope, new Context(args.args.get(3)), args.args.get(1), false);
                } else if ("from".equals(args.args.get(1)) && "--unfiltered".equals(args.args.get(3))) {
                    return new Opts(scope, new Context(args.args.get(2)), null, true);
                } else {
                    return null;
                }
            case 5:
                // propName and context and unfiltered
                if ("from".equals(args.args.get(2)) && "--unfiltered".equals(args.args.get(4))) {
                    return new Opts(scope, new Context(args.args.get(3)), args.args.get(1), true);
                }
                // fall-through
            default:
                return null;
        }
    }

    protected boolean printContext(Context context, Scope scope, Iterable<Prop> props, String likePropName, boolean unfiltered) {
        if (props == null) {
            return false;
        }
        List<Prop> properties = collect(props);
        Collections.sort(properties);
        boolean printedHeader = false;
        boolean printedSomething = false;
        for (Prop prop : properties) {
            if ((likePropName == null) || matches(prop, likePropName)) {
                if (!printedHeader) {
                    Output.print("Properties from ^b^%s^r^", context.name);
                    printedHeader = true;
                }
                Output.print("   ^b^%s^r^ = ^cyan^%s^r^%s", prop.name, (unfiltered ? prop.unfilteredValue : prop.value()),
                        getSuffix(prop, scope));
                printedSomething = true;
            }
        }
        return printedSomething;
    }
    
    protected List<Prop> collect(Iterable<Prop> props) {
        List<Prop> properties = new ArrayList<Prop>();
        for (Prop prop : props) {
            if (accept(prop)) {
                properties.add(prop);
            }
        }
        return properties;
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

    protected boolean accept(Prop prop) {
        return (prop.loc() != PropFile.Loc.System);
    }

    protected String getSuffix(Prop prop, Scope scope) {
        if (!Scope.Default.equals(scope) && !Scope.Default.equals(prop.scope())) { // TODO - is this AND or OR?
            return " ^magenta^**^r^";
        }
        return "";
    }

    protected String getNothingMessageSuffix() {
        return " locally (try ^b^get-all^r^)";
    }

    protected void printAppendix(Scope scope) {
        if (!Scope.Default.equals(scope)) {
            Output.print("^magenta^**^r^ indicates %s-scoped property.", scope.name);
        }
    }

    protected void printNothingMessage(Context context, String propName) {
        if (context == null) {
            if (propName == null) {
                Output.print("No properties in any context found%s.", getNothingMessageSuffix());
            } else {
                Output.print("No property like ^b^%s^r^ found in any context%s.", propName, getNothingMessageSuffix());
            }
        } else if (propName == null) {
            Output.print("No context ^b^%s^r^ found%s.", context, getNothingMessageSuffix());
        } else {
            Output.print("No property like ^b^%s^r^ found in context ^b^%s^r^%s.", propName, context, getNothingMessageSuffix());
        }
    }

}
