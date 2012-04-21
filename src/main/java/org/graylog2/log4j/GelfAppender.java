package org.graylog2.log4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.MDC;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LocationInfo;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.ThrowableInformation;
import org.graylog2.GelfConnection;
import org.graylog2.GelfMessage;
import org.graylog2.GelfMessageUtil;
import org.graylog2.SyslogLevel;
import org.json.simple.JSONValue;

/**
 * @author Anton Yakimov
 * @author Jochen Schalanda
 */
public class GelfAppender
  extends AppenderSkeleton {

  private static final String ORIGIN_HOST_KEY = "originHost";
  private static final String LOGGER_NAME = "logger";
  private static final String LOGGER_NDC = "loggerNdc";
  private static final String THREAD_NAME = "thread";
  private static final String JAVA_TIMESTAMP = "timestampMs";

  private static boolean hasGetTimeStamp = true;
  private static Method methodGetTimeStamp = null;


    private String graylogHost;
    private static String originHost;
    private int graylogPort = GelfConnection.DEFAULT_PORT;
    private String facility;
    private GelfConnection _connection;
    private boolean extractStacktrace;
    private boolean addExtendedInformation;
    private Map<String, String> fields;

    public void setAdditionalFields(String additionalFields) {
        fields = (Map<String, String>) JSONValue.parse(additionalFields.replaceAll("'", "\""));
    }

    public int getGraylogPort() {
        return graylogPort;
    }

    public void setGraylogPort(int graylogPort) {
        this.graylogPort = graylogPort;
    }

    public String getGraylogHost() {
        return graylogHost;
    }

    public void setGraylogHost(String graylogHost) {
        this.graylogHost = graylogHost;
    }

    public String getFacility() {
        return facility;
    }

    public void setFacility(String facility) {
        this.facility = facility;
    }

    public boolean isExtractStacktrace() {
        return extractStacktrace;
    }

    public void setExtractStacktrace(boolean extractStacktrace) {
        this.extractStacktrace = extractStacktrace;
    }

    public String getOriginHost() {
        if (originHost == null) {
            originHost = getLocalHostName();
        }
        return originHost;
    }

    private String getLocalHostName() {
        String hostName = null;
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            errorHandler.error("Unknown local hostname", e, ErrorCode.GENERIC_FAILURE);
        }

        return hostName;
    }

    public void setOriginHost(String originHost) {
        this.originHost = originHost;
    }

    public boolean isAddExtendedInformation() {
        return addExtendedInformation;
    }

    public void setAddExtendedInformation(boolean addExtendedInformation) {
        this.addExtendedInformation = addExtendedInformation;
    }
    
    public Map<String, String> getFields() {
        if (fields == null) {
            fields = new HashMap<String, String>();
        }
        return Collections.unmodifiableMap(fields);
    }

    @Override
    public void activateOptions() {
        try {
            _connection = new GelfConnection(InetAddress.getByName( graylogHost ), graylogPort);
        } catch (UnknownHostException e) {
            errorHandler.error("Unknown Graylog2 hostname:" + getGraylogHost(), e, ErrorCode.WRITE_FAILURE);
        } catch (Exception e) {
            errorHandler.error("Socket exception", e, ErrorCode.WRITE_FAILURE);
        }
    }

    @Override
    protected void append(LoggingEvent event) {
        GelfMessage gelfMessage = makeMessage( event );

      if( _connection == null || !_connection.send( gelfMessage )) {
            errorHandler.error("Could not send GELF message");
        }
    }

  public void close() {
    _connection.close();
    }

    public boolean requiresLayout() {
        return false;
    }

  private GelfMessage makeMessage(LoggingEvent event) {
    long timeStamp = getTimeStamp( event );
    Level level = event.getLevel();

    LocationInfo locationInformation = event.getLocationInformation();
    String file = locationInformation.getFileName();
    Integer lineNumber = null;
    try
    {
      final String lineInfo = locationInformation.getLineNumber();
      if( null != lineInfo )
      {
        lineNumber = Integer.parseInt( lineInfo );
      }
    }
    catch( final NumberFormatException nfe )
    {
      //ignore
    }

    String renderedMessage = event.getRenderedMessage();

    if (renderedMessage == null) {
      renderedMessage = "";
    }

    final String shortMessage = GelfMessageUtil.truncateShortMessage( renderedMessage );

    if (isExtractStacktrace()) {
      ThrowableInformation throwableInformation = event.getThrowableInformation();
      if (throwableInformation != null) {
        renderedMessage += "\n\r" + GelfMessageUtil.extractStacktrace( throwableInformation.getThrowable() );
      }
    }

    final GelfMessage gelfMessage = new GelfMessage();
    gelfMessage.setShortMessage( shortMessage );
    gelfMessage.setFullMessage( renderedMessage );
    gelfMessage.setJavaTimestamp( timeStamp );
    gelfMessage.setLevel( SyslogLevel.values()[ level.getSyslogEquivalent() ] );
    if( null != lineNumber )
    {
      gelfMessage.setLine( lineNumber );
    }
    if( null != file )
    {
      gelfMessage.setFile( file );
    }

    if (getOriginHost() != null) {
      gelfMessage.setHostname( getOriginHost() );
    }

    if (getFacility() != null) {
      gelfMessage.setFacility(getFacility());
    }

    Map<String, String> fields = getFields();
    for (Map.Entry<String, String> entry : fields.entrySet()) {
      if (entry.getKey().equals(ORIGIN_HOST_KEY) && gelfMessage.getHostname() == null) {
        gelfMessage.setHostname( fields.get( ORIGIN_HOST_KEY ) );
      } else {
        gelfMessage.getAdditionalFields().put( entry.getKey(), entry.getValue() );
      }
    }

    if (isAddExtendedInformation()) {

      gelfMessage.getAdditionalFields().put( THREAD_NAME, event.getThreadName() );
      gelfMessage.getAdditionalFields().put( LOGGER_NAME, event.getLoggerName() );

      gelfMessage.getAdditionalFields().put( JAVA_TIMESTAMP, Long.toString(gelfMessage.getJavaTimestamp()) );

      // Get MDC and add a GELF field for each key/value pair
      @SuppressWarnings( "unchecked" ) Map<String, Object> mdc = (Map<String, Object>) MDC.getContext();

      if(mdc != null) {
        for(Map.Entry<String, Object> entry : mdc.entrySet()) {

          gelfMessage.getAdditionalFields().put( entry.getKey(), entry.getValue().toString() );

        }
      }

      // Get NDC and add a GELF field
      String ndc = event.getNDC();

      if(ndc != null) {

        gelfMessage.getAdditionalFields().put( LOGGER_NDC, ndc );

      }
    }

    return gelfMessage;
  }

  static long getTimeStamp(LoggingEvent event) {

    long timeStamp = System.currentTimeMillis();

    if(hasGetTimeStamp && methodGetTimeStamp == null) {

      hasGetTimeStamp = false;

      Method[] declaredMethods = event.getClass().getDeclaredMethods();
      for(Method m : declaredMethods) {
        if (m.getName().equals("getTimeStamp")) {
          methodGetTimeStamp = m;
          hasGetTimeStamp = true;

          break;
        }
      }
    }

    if(hasGetTimeStamp) {

      try {
        timeStamp = (Long) methodGetTimeStamp.invoke(event);
      } catch (IllegalAccessException e) {
        // Just return the current timestamp
      } catch (InvocationTargetException e) {
        // Just return the current timestamp
      }
    }

    return timeStamp;
  }
}