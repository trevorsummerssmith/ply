package net.ocheyedan.ply.ext.cmd.config;

import net.ocheyedan.ply.ext.cmd.Args;

/**
 * User: blangel
 * Date: 1/1/12
 * Time: 4:04 PM
 *
 * A {@link net.ocheyedan.ply.ext.cmd.Command} to prepend a value to a property value within the project's configuration.
 */
public final class Prepend extends Append {

    public Prepend(Args args) {
        super(args);
    }

    protected String getFromExisting(String existing, String addition) {
        return addition + (existing.isEmpty() ? existing : " " + existing);
    }

}
