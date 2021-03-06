/*
 * QDCCrosswalk.java
 *
 * Version: $Revision: 3705 $
 *
 * Date: $Date: 2009-04-11 18:02:24 +0100 (Sat, 11 Apr 2009) $
 *
 * Copyright (c) 2002-2005, Hewlett-Packard Company and Massachusetts
 * Institute of Technology.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * - Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * - Neither the name of the Hewlett-Packard Company nor the name of the
 * Massachusetts Institute of Technology nor the names of their
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */

package org.dspace.content.crosswalk;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.DCValue;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataSchema;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.SelfNamedPlugin;
import org.jdom.Attribute;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.Namespace;
import org.jdom.input.SAXBuilder;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

/**
 * Configurable QDC Crosswalk
 * <p>
 * This class supports multiple dissemination crosswalks from DSpace
 * internal data to the Qualified Dublin Core XML format
 *  (see <a href="http://dublincore.org/">http://dublincore.org/</a>
 * <p>
 * It registers multiple Plugin names, which it reads from
 * the DSpace configuration as follows:
 *
 * <h3>Configuration</h3>
 * Every key starting with <code>"crosswalk.qdc.properties."</code> describes a
 * QDC crosswalk.  Everything after the last period is the <em>plugin instance</em>,
 * and the value is the pathname (relative to <code><em>dspace.dir</em>/config</code>)
 * of the crosswalk configuration file.
 * <p>
 * You can have two aliases point to the same crosswalk,
 * just add two configuration entries with the same value, e.g.
 * <pre>
 *    crosswalk.qdc.properties.QDC = xwalk/qdc.properties
 *    crosswalk.qdc.properties.default = xwalk/qdc.properties
 * </pre>
 * The first line creates a plugin with the name <code>"QDC"</code>
 * which is configured from the file <em>dspace-dir</em><code>/xwalk/qdc.properties</code>.
 * <p>
 * Since there is significant overhead in reading the properties file to
 * configure the crosswalk, and a crosswalk instance may be used any number
 * of times, we recommend caching one instance of the crosswalk for each
 * alias and simply reusing those instances. The PluginManager does
 * this by default.
 * <p>
 * Each named crosswalk has two other types of configuration lines:
 * <p>
 * XML Namespaces: all XML namespace prefixes used in the XML fragments below
 * <em>must</em> be defined in the configuration as follows.  Add a line of
 * the form: <pre>
 *  crosswalk.qdc.namespace.{NAME}.{prefix} = {namespace-URI}</pre>
 * e.g. for the namespaces <code>dc</code> and <code>dcterms</code>
 * in the plugin named <code>QDC</code>, add these lines:
 * <pre>crosswalk.qdc.namespace.QDC.dc = http://purl.org/dc/elements/1.1/
 * crosswalk.qdc.namespace.QDC.dcterms = http://purl.org/dc/terms/</pre>
 *
 * <p>
 * Finally, you need to declare an XML Schema URI for the plugin, with
 * a line of the form <pre>
 *  crosswalk.qdc.schema.{NAME} = {schema-URI}</pre>
 * for example,
 * <pre>crosswalk.qdc.schemaLocation.QDC  = \
 *  http://purl.org/dc/terms/ \
 *  http://dublincore.org/schemas/xmls/qdc/2003/04/02/qualifieddc.xsd</pre>
 *
 * @author Larry Stone
 * @version $Revision: 3705 $
 */
public class QDCCrosswalk extends SelfNamedPlugin
    implements DisseminationCrosswalk, IngestionCrosswalk
{
    /** log4j category */
    private static Logger log = Logger.getLogger(QDCCrosswalk.class);

    // map of qdc to JDOM Element
    private HashMap qdc2element = new HashMap();

    // map of JDOM Element to qdc DCValue
    private HashMap element2qdc = new HashMap();

    // the XML namespaces from config file for this name.
    private Namespace namespaces[] = null;

    private static final Namespace DCTERMS_NS =
        Namespace.getNamespace("dcterms", "http://purl.org/dc/terms/");

    // sentinal: done init?
    private boolean inited = false;

    // my plugin name
    private String myName = null;

    // prefix of all DSpace Configuration entries.
    private static final String CONFIG_PREFIX = "crosswalk.qdc";

    // XML schemaLocation fragment for this crosswalk, from config.
    private String schemaLocation = null;

    private static final Namespace XLINK_NS =
        Namespace.getNamespace("xlink", "http://www.w3.org/TR/xlink");

    private static XMLOutputter outputUgly = new XMLOutputter();
    private static XMLOutputter outputPretty = new XMLOutputter(Format.getPrettyFormat());
    private static SAXBuilder builder = new SAXBuilder();

    // GWaller 1/11/10 IssueID #486 lang attributes are only allowed on element that don't have the following type attribute (according to the dcterms schema)
    // NOTE: these are simply attribute values, not element named within a namespace. The prefix e.g. dcterms should match what is specified in QDC.properties
    private String[] TYPE_VALUES_LANG_PROHIBITED = {"dcterms:MESH", 
    												"dcterms:DDC", 
    												"dcterms:LCC", 
    												"dcterms:UDC", 
    												"dcterms:Period", 
    												"dcterms:W3CDTF", 
    												"dcterms:DCMIType", 
    												"dcterms:IMT", 
    												"dcterms:URI", 
    												"dcterms:ISO639-2", 
    												"dcterms:RFC1766", 
    												"dcterms:RFC3066", 
    												"dcterms:Point", 
    												"dcterms:ISO3166", 
    												"dcterms:Box", 
    												"dcterms:TGN"};
    
    /**
     * Fill in the plugin-name table from DSpace configuration entries
     * for configuration files for flavors of QDC crosswalk:
     */
    private static String aliases[] = null;
    static
    {
        List aliasList = new ArrayList();
        Enumeration pe = ConfigurationManager.propertyNames();
        String propname = CONFIG_PREFIX + ".properties.";
        while (pe.hasMoreElements())
        {
            String key = (String)pe.nextElement();
            if (key.startsWith(propname))
                aliasList.add(key.substring(propname.length()));
        }
        aliases = (String[])aliasList.toArray(new String[aliasList.size()]);
    }

    public static String[] getPluginNames()
    {
        return aliases;
    }

    // utility: return "fully qualified" name of XML element, for a
    // hashtable key to use on ingesting elements.
    // Format is {prefix:}name  where prefix is optional.
    private String makeQualifiedTagName(Element element)
    {
        String prefix = "";
        Namespace ns = element.getNamespace();
        if (ns != null)
            prefix = ns.getPrefix() + ":";
        
        String tagName;
        String nsQualifier = element.getAttributeValue("type", DisseminationCrosswalk.XSI_NS);
        
        if (nsQualifier == null || nsQualifier.length() < 1)
        {
            String qualifier = element.getAttributeValue("type");
            if (qualifier == null || qualifier.length() < 1)
            {
            	tagName = prefix+element.getName();
            }
            else
            {
            	tagName = prefix+element.getName()+qualifier;
            }
        }
        else
        {
        	tagName = prefix+element.getName()+nsQualifier;
        }
        
        return tagName;
    }

    /**
     * Initialize Crosswalk table from a properties file
     * which itself is the value of the DSpace configuration property
     * "crosswalk.qdc.properties.X", where "X" is the alias name of this instance.
     * Each instance may be configured with a separate mapping table.
     *
     * The QDC crosswalk configuration properties follow the format:
     *
     *  {qdc-element} = {XML-fragment}
     *
     *  1. qualified DC field name is of the form (qualifier is optional)
     *       {MDschema}.{element}.{qualifier}
     *
     *      e.g.  dc.contributor.author
     *            dc.title
     *
     *  2. XML fragment is prototype of metadata element, with empty
     *     placeholders for value).
     *
     * Example properties line:
     *
     *  dc.coverage.temporal = <dcterms:temporal />
     */
    private void init()
        throws CrosswalkException, IOException
    {
        if (inited)
            return;
        inited = true;

        myName = getPluginInstanceName();
        if (myName == null)
            throw new CrosswalkInternalException("Cannot determine plugin name, "+
                       "You must use PluginManager to instantiate QDCCrosswalk so the instance knows its name.");

        // grovel DSpace configuration for namespaces
        List nsList = new ArrayList();
        Enumeration pe = ConfigurationManager.propertyNames();
        String propname = CONFIG_PREFIX + ".namespace."+ myName +".";
        while (pe.hasMoreElements())
        {
            String key = (String)pe.nextElement();
            if (key.startsWith(propname))
                nsList.add(Namespace.getNamespace(key.substring(propname.length()),
                             ConfigurationManager.getProperty(key)));
        }
        nsList.add(Namespace.XML_NAMESPACE);
        
        // GWaller 1/11/10 IssueID #486 type attribute belongs to the XSI prefix
        nsList.add(DisseminationCrosswalk.XSI_NS);
        
        namespaces = (Namespace[])nsList.toArray(new Namespace[nsList.size()]);

        // get XML schemaLocation fragment from config
        schemaLocation = ConfigurationManager.getProperty(CONFIG_PREFIX + ".schemaLocation."+ myName);

        // read properties
        String cmPropName = CONFIG_PREFIX+".properties."+myName;
        String propsFilename = ConfigurationManager.getProperty(cmPropName);
        if (propsFilename == null)
            throw new CrosswalkInternalException("Configuration error: "+
                "No properties file configured for QDC crosswalk named \""+myName+"\"");

        String parent = ConfigurationManager.getProperty("dspace.dir") +
            File.separator + "config" + File.separator;
        File propsFile = new File(parent, propsFilename);
        Properties qdcProps = new Properties();
        FileInputStream pfs = null;
        try
        {
            pfs = new FileInputStream(propsFile);
            qdcProps.load(pfs);
        }
        finally
        {
            if (pfs != null)
                try { pfs.close(); } catch (IOException ioe) { }
        }

        // grovel properties to initialize qdc->element and element->qdc maps.
        // evaluate the XML fragment with a wrapper including namespaces.
        String postlog = "</wrapper>";
        StringBuffer prologb = new StringBuffer("<wrapper");
        for (int i = 0; i < namespaces.length; ++i)
        {
            prologb.append(" xmlns:");
            prologb.append(namespaces[i].getPrefix());
            prologb.append("=\"");
            prologb.append(namespaces[i].getURI());
            prologb.append("\"");
        }
        prologb.append(">");
        String prolog = prologb.toString();
        pe = qdcProps.propertyNames();
        while (pe.hasMoreElements())
        {
            String qdc = (String)pe.nextElement();
            String val = qdcProps.getProperty(qdc);
            try
            {
                Document d = builder.build(new StringReader(prolog+val+postlog));
                Element element = (Element)d.getRootElement().getContent(0);
                qdc2element.put(qdc, element);
                element2qdc.put(makeQualifiedTagName(element), qdc);
                log.debug("Building Maps: qdc=\""+qdc+"\", element=\""+element.toString()+"\"");
            }
            catch (org.jdom.JDOMException je)
            {
                throw new CrosswalkInternalException("Failed parsing XML fragment in properties file: \""+prolog+val+postlog+"\": "+je.toString());
            }
        }
    }

    public Namespace[] getNamespaces()
    {
        try
        {
            init();
        }
        catch (Exception e)
        {
        }
        return namespaces;
    }

    public String getSchemaLocation()
    {
        try
        {
            init();
        }
        catch (Exception e)
        {
        }
        return schemaLocation;
    }

    /**
     * Returns object's metadata in MODS format, as XML structure node.
     */
    public List disseminateList(DSpaceObject dso)
        throws CrosswalkException,
               IOException, SQLException, AuthorizeException
    {
        return disseminateListInternal(dso, true);
    }

    // GWaller 1/11/10 IssueID #486 lang attributes aren't allowed on elements with some type attributes
    private boolean allowedLangAttribute(Element e){
    	boolean result = true;
    	
    	// Check the element has a type attribute first
    	Attribute elementTypeAtt = e.getAttribute("type", DisseminationCrosswalk.XSI_NS);
    	if (elementTypeAtt != null){
    		// Now check the value of the type attribute to see if its one of the types that can't have a lang attribute
    		String typeValue = elementTypeAtt.getValue();
    		for (String typeToCompare : TYPE_VALUES_LANG_PROHIBITED){
    			if (typeValue.equalsIgnoreCase(typeToCompare)){
    				result = false;
    				break;
    			}
    		}
    		
    	}
    	
    	return result;
    }
    
    
    private List disseminateListInternal(DSpaceObject dso, boolean addSchema)
        throws CrosswalkException,
               IOException, SQLException, AuthorizeException
    {
        if (dso.getType() != Constants.ITEM)
            throw new CrosswalkObjectNotSupported("QDCCrosswalk can only crosswalk an Item.");
        Item item = (Item)dso;
        init();

        DCValue[] dc = item.getMetadata(Item.ANY, Item.ANY, Item.ANY, Item.ANY);
        List result = new ArrayList(dc.length);
        for (int i = 0; i < dc.length; i++)
        {
            // Compose qualified DC name - schema.element[.qualifier]
            // e.g. "dc.title", "dc.subject.lcc", "lom.Classification.Keyword"
            String qdc = dc[i].schema+"."+
                         ((dc[i].qualifier == null) ? dc[i].element
                            : (dc[i].element + "." + dc[i].qualifier));

            Element elt = (Element)qdc2element.get(qdc);

            // only complain about missing elements in the DC schema:
            if (elt == null)
            {
                if (dc[i].schema.equals(MetadataSchema.DC_SCHEMA))
                    log.warn("WARNING: "+myName+": No QDC mapping for \"" + qdc+"\"");
            }
            else
            {
                Element qe = (Element)elt.clone();
                qe.setText(dc[i].value);
                if (addSchema && schemaLocation != null)
                    qe.setAttribute("schemaLocation", schemaLocation, XSI_NS);
                if (dc[i].language != null && allowedLangAttribute(qe))
                    qe.setAttribute("lang", dc[i].language, Namespace.XML_NAMESPACE);
                result.add(qe);
            }
        }
        return result;
    }

    public Element disseminateElement(DSpaceObject dso)
        throws CrosswalkException,
               IOException, SQLException, AuthorizeException
    {
        init();
        Element root = new Element("qualifieddc", DCTERMS_NS);
        if (schemaLocation != null)
            root.setAttribute("schemaLocation", schemaLocation, XSI_NS);
        root.addContent(disseminateListInternal(dso, false));
        return root;
    }

    public boolean canDisseminate(DSpaceObject dso)
    {
        return true;
    }

    public void ingest(Context context, DSpaceObject dso, Element root)
        throws CrosswalkException, IOException, SQLException, AuthorizeException
    {
        init();

        // NOTE:  don't bother comparing namespace on root element
        // because DCMI doesn't specify one, and every app uses its
        // own..  just give up in the face of this madness and accept
        // anything with the right name.
        if (!(root.getName().equals("qualifieddc")))
            throw new MetadataValidationException("Wrong root element for Qualified DC: "+root.toString());
        ingest(context, dso, root.getChildren());
    }

    public void ingest(Context context, DSpaceObject dso, List ml)
        throws CrosswalkException, IOException, SQLException, AuthorizeException
    {
        init();

        // for now, forget about any targets but item.
        if (dso.getType() != Constants.ITEM)
            throw new CrosswalkInternalException("Wrong target object type, QDCCrosswalk can only crosswalk to an Item.");

        Item item = (Item)dso;

        Iterator mi = ml.iterator();
        while (mi.hasNext())
        {
            Element me = (Element)mi.next();
            String key = makeQualifiedTagName(me);

            // if the root element gets passed here, recurse:
            if (me.getName().equals("qualifieddc"))
                ingest(context, dso, me.getChildren());

            else if (element2qdc.containsKey(key))
            {
                String qdc[] = ((String)element2qdc.get(key)).split("\\.");

                // get language - prefer xml:lang, accept lang.
                String lang = me.getAttributeValue("lang", Namespace.XML_NAMESPACE);
                if (lang == null)
                    lang = me.getAttributeValue("lang");

                if (qdc.length == 3)
                    item.addMetadata(qdc[0], qdc[1], qdc[2], lang, me.getText());
                else if (qdc.length == 2)
                    item.addMetadata(qdc[0], qdc[1], null,   lang, me.getText());
                else
                    throw new CrosswalkInternalException("Unrecognized format in QDC element identifier for key=\""+key+"\", qdc=\""+(String)element2qdc.get(key)+"\"");
            }
            else
                log.warn("WARNING: "+myName+": No mapping for Element=\"" + key+"\" to qdc.");
        }
    }

    public boolean preferList()
    {
        return true;
    }
}
