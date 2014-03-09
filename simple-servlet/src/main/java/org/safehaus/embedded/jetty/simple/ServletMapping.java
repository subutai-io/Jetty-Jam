package org.safehaus.embedded.jetty.simple;


import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.servlet.Servlet;


/**
 * A servlet mapping.
 */
@Retention( RetentionPolicy.RUNTIME )
@Target( ElementType.FIELD )
@Inherited
public @interface ServletMapping {
    Class<? extends Servlet> servlet();
    String spec();
}