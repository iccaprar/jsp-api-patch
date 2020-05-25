package javax.servlet.jsp.el;

import java.beans.FeatureDescriptor;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import javax.el.ELClass;
import javax.el.ELContext;
import javax.el.ELResolver;
import javax.el.ImportHandler;
import javax.servlet.jsp.JspContext;
import javax.servlet.jsp.PageContext;

/**
 * We implemented this patch to solve the huge memory problems with the Unified Expression Language v3.0 in Tomcat 8
 * when accessing unscoped attributes (e.g. "${not empty menu.children}", "${menu}").
 *
 * For reference, a scoped attribute is something like "${requestScope.menu}" and it is not affected.
 *
 * Any JSP expression like "${not empty menu.children}" is triggering 4 class loading attempts of a menu.class, from the following locations
 * (@see javax.el.ImportHandler for the locations):
 * <ul>
 *     <li>java/lang/menu.class</li>
 *     <li>javax/servlet/http/menu.class</li>
 *     <li>javax/servlet/menu.class</li>
 *     <li>javax/servlet/jsp/menu.class</li>
 * </ul>
 *
 * This needs to be done because with EL 3.0, there is now support for static fields and methods in EL, using expressions like: "${Boolean.TRUE}".
 * The classes for the static fields/methods need to be imported, of course, in the JSP/TAG header.
 *
 * Details of this behavior are found in the classes "org.apache.el.parser.AstIdentifier" and the class this one replaces.
 *
 * Tomcat implemented a performance workaround for the simple attributes like "${menu}", by marking them in the EL context as identifiers
 * (see the code in AstIdentifier) and then checking this in the ScopedAttributeELResolver and skipping the class loading completely.
 *
 * But our JSPs and tags are still full of EL code (and even worse, we have it in nested loops ...) so the optimization of Tomcat does not help.
 *
 * We are optimizing this behavior trying to break as little as possible the EL 3.0 spec, by avoiding the whole class resolution and loading
 * when the resolved property starts with a small letter (classes should be named with uppercase first letter, anyway!!). If we have such things
 * in the code, the pages will not compile, but we will notice that quickly.
 *
 * The problem was reported to Apache and the situation was improved by introducing the above optimization when the unscoped attributes
 * are accessed individually:
 * <ul>
 *     <li>fix for the direct references to attributes: https://bz.apache.org/bugzilla/show_bug.cgi?id=57583</li>
 *     <li>https://bz.apache.org/bugzilla/show_bug.cgi?id=57773</li>
 * </ul>
 *
 * Others found more radical solutions to this problem:
 * <ul>
 *     <li>https://stackoverflow.com/questions/35475980/is-it-possible-to-disable-support-for-referencing-static-fields-and-methods-for</li>
 * </ul>
 *
 * The resulting JAR is placed in $CATALINA_BASE/lib, making use of Tomcat's class loading mechanism.
 * The common class loader contains additional classes that are made visible to both Tomcat internal classes and to all web applications.
 * The locations searched by this class loader are defined by the common.loader property in $CATALINA_BASE/conf/catalina.properties.
 * The default setting will search the following locations in the order they are listed:
 * <ul>
 *     <li>Unpacked classes and resources in $CATALINA_BASE/lib</li>
 *     <li>JAR files in $CATALINA_BASE/lib</li>
 *     <li>Unpacked classes and resources in $CATALINA_HOME/lib</li>
 *     <li>JAR files in $CATALINA_HOME/lib</li>
 * </ul>
 */
public class ScopedAttributeELResolver extends ELResolver {

    private static final Class<?> AST_IDENTIFIER_KEY;

    public ScopedAttributeELResolver() {
        // empty
    }

    public Object getValue(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context);
        Object result = null;
        if (base == null) {
            context.setPropertyResolved(base, property);
            if (property != null) {
                String key = property.toString();
                PageContext page = (PageContext)context.getContext(JspContext.class);
                result = page.findAttribute(key);
                if (result == null) {
                    boolean resolveClass = true;
                    if (AST_IDENTIFIER_KEY != null) {
                        Boolean value = (Boolean)context.getContext(AST_IDENTIFIER_KEY);
                        if (value != null && value) {
                            resolveClass = false;
                        }
                    }

                    // START CUSTOMIZATION
                    if (!Character.isUpperCase(key.charAt(0))) {
                        resolveClass = false;
                    }
                    // END CUSTOMIZATION

                    ImportHandler importHandler = context.getImportHandler();
                    if (importHandler != null) {
                        Class<?> clazz = null;
                        if (resolveClass) {
                            clazz = importHandler.resolveClass(key);
                        }

                        if (clazz != null) {
                            result = new ELClass(clazz);
                        }

                        if (result == null) {
                            clazz = importHandler.resolveStatic(key);
                            if (clazz != null) {
                                try {
                                    result = clazz.getField(key).get(null);
                                } catch (IllegalAccessException | NoSuchFieldException | SecurityException | IllegalArgumentException var11) {
                                    // ignore everything
                                }
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    public Class<Object> getType(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context);
        if (base == null) {
            context.setPropertyResolved(base, property);
            return Object.class;
        } else {
            return null;
        }
    }

    public void setValue(ELContext context, Object base, Object property, Object value) {
        Objects.requireNonNull(context);
        if (base == null) {
            context.setPropertyResolved(base, property);
            if (property != null) {
                String key = property.toString();
                PageContext page = (PageContext)context.getContext(JspContext.class);
                int scope = page.getAttributesScope(key);
                if (scope != 0) {
                    page.setAttribute(key, value, scope);
                } else {
                    page.setAttribute(key, value);
                }
            }
        }

    }

    public boolean isReadOnly(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context);
        if (base == null) {
            context.setPropertyResolved(base, property);
        }

        return false;
    }

    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        PageContext ctxt = (PageContext)context.getContext(JspContext.class);
        List<FeatureDescriptor> list = new ArrayList();
        Enumeration e = ctxt.getAttributeNamesInScope(1);

        Object value;
        String name;
        FeatureDescriptor descriptor;
        while(e.hasMoreElements()) {
            name = (String)e.nextElement();
            value = ctxt.getAttribute(name, 1);
            descriptor = new FeatureDescriptor();
            descriptor.setName(name);
            descriptor.setDisplayName(name);
            descriptor.setExpert(false);
            descriptor.setHidden(false);
            descriptor.setPreferred(true);
            descriptor.setShortDescription("page scoped attribute");
            descriptor.setValue("type", value.getClass());
            descriptor.setValue("resolvableAtDesignTime", Boolean.FALSE);
            list.add(descriptor);
        }

        e = ctxt.getAttributeNamesInScope(2);

        while(e.hasMoreElements()) {
            name = (String)e.nextElement();
            value = ctxt.getAttribute(name, 2);
            descriptor = new FeatureDescriptor();
            descriptor.setName(name);
            descriptor.setDisplayName(name);
            descriptor.setExpert(false);
            descriptor.setHidden(false);
            descriptor.setPreferred(true);
            descriptor.setShortDescription("request scope attribute");
            descriptor.setValue("type", value.getClass());
            descriptor.setValue("resolvableAtDesignTime", Boolean.FALSE);
            list.add(descriptor);
        }

        if (ctxt.getSession() != null) {
            e = ctxt.getAttributeNamesInScope(3);

            while(e.hasMoreElements()) {
                name = (String)e.nextElement();
                value = ctxt.getAttribute(name, 3);
                descriptor = new FeatureDescriptor();
                descriptor.setName(name);
                descriptor.setDisplayName(name);
                descriptor.setExpert(false);
                descriptor.setHidden(false);
                descriptor.setPreferred(true);
                descriptor.setShortDescription("session scoped attribute");
                descriptor.setValue("type", value.getClass());
                descriptor.setValue("resolvableAtDesignTime", Boolean.FALSE);
                list.add(descriptor);
            }
        }

        e = ctxt.getAttributeNamesInScope(4);

        while(e.hasMoreElements()) {
            name = (String)e.nextElement();
            value = ctxt.getAttribute(name, 4);
            descriptor = new FeatureDescriptor();
            descriptor.setName(name);
            descriptor.setDisplayName(name);
            descriptor.setExpert(false);
            descriptor.setHidden(false);
            descriptor.setPreferred(true);
            descriptor.setShortDescription("application scoped attribute");
            descriptor.setValue("type", value.getClass());
            descriptor.setValue("resolvableAtDesignTime", Boolean.FALSE);
            list.add(descriptor);
        }

        return list.iterator();
    }

    public Class<String> getCommonPropertyType(ELContext context, Object base) {
        return base == null ? String.class : null;
    }

    static {
        Class key = null;

        try {
            key = Class.forName("org.apache.el.parser.AstIdentifier");
        } catch (Exception var2) {
        }

        AST_IDENTIFIER_KEY = key;
    }
}
