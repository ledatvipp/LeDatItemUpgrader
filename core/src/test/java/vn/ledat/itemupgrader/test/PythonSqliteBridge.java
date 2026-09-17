package vn.ledat.itemupgrader.test;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** TEST ONLY JDBC-interface transport, NOT a production driver. Runs real repository prepared SQL on
 * Python's sqlite3 with one persistent connection/process. Unknown JDBC methods fail loudly.
 * Query timeout is only recorded here; driver timeout behavior is explicitly NOT tested. */
final class PythonSqliteBridge implements AutoCloseable {
    private final Process process;private final BufferedReader reader;private final BufferedWriter writer;
    private final Connection connection;private boolean auto=true,closed;
    boolean failAfterNextCommit;
    int commits,rollbacks;
    PythonSqliteBridge(Path helper,Path database)throws IOException {
        process=new ProcessBuilder("python3","-u",helper.toAbsolutePath().toString(),database.toAbsolutePath().toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        reader=process.inputReader(StandardCharsets.UTF_8);writer=process.outputWriter(StandardCharsets.UTF_8);
        connection=proxy(Connection.class,this::connectionCall);
    }
    Connection connection(){return connection;}
    private Object connectionCall(Object self,Method method,Object[] args)throws Throwable {
        return switch(method.getName()) {
            case "prepareStatement"->statement((String)args[0],true);
            case "createStatement"->statement(null,false);
            case "getAutoCommit"->auto;
            case "setAutoCommit"->{command("AUTO",String.valueOf(args[0]));auto=(boolean)args[0];yield null;}
            case "commit"->{command("COMMIT");commits++;if(failAfterNextCommit){failAfterNextCommit=false;throw new SQLException("synthetic lost COMMIT reply");}yield null;}
            case "rollback"->{command("ROLLBACK");rollbacks++;yield null;}
            case "getCatalog","getSchema"->null;
            case "getMetaData"->proxy(DatabaseMetaData.class,(p,m,a)->switch(m.getName()) {
                case "getIndexInfo"->query("SELECT il.name AS INDEX_NAME,(1-il.\"unique\") AS NON_UNIQUE,ii.seqno+1 AS ORDINAL_POSITION,ii.name AS COLUMN_NAME,NULL AS FILTER_CONDITION FROM pragma_index_list(?) il JOIN pragma_index_info(il.name) ii WHERE ?=0 OR il.\"unique\"=1",List.of(a[2],Boolean.TRUE.equals(a[3])?1:0));
                case "getPrimaryKeys"->query("SELECT ? AS TABLE_NAME,name AS COLUMN_NAME,pk AS KEY_SEQ FROM pragma_table_info(?) WHERE pk>0 ORDER BY pk",List.of(a[2],a[2]));
                case "getTables"->query("SELECT name AS TABLE_NAME,CASE type WHEN 'table' THEN 'TABLE' ELSE 'VIEW' END AS TABLE_TYPE FROM sqlite_master WHERE type IN ('table','view') AND name LIKE ? ESCAPE '\\'",List.of(a[2]));
                case "getSearchStringEscape"->"\\";
                case "getDatabaseProductName"->"SQLite"; // actual DB engine; this remains a TEST-ONLY transport, not xerial
                case "getDriverName"->"TEST_ONLY_PythonSqliteBridge";
                default->objectOrUnsupported(p,m,a);
            });
            case "isClosed"->closed;
            case "close"->{close();yield null;}
            default->objectOrUnsupported(self,method,args);
        };
    }
    private Object statement(String sql,boolean prepared) {
        Map<Integer,Object> parameters=new TreeMap<>();boolean[] statementClosed={false};
        InvocationHandler h=(self,method,args)->switch(method.getName()) {
            case "setString","setInt","setLong","setBytes","setBoolean"->{parameters.put((int)args[0],args[1]);yield null;}
            case "setNull"->{parameters.put((int)args[0],null);yield null;}
            case "setQueryTimeout","setMaxRows","setFetchSize","clearWarnings"->null;
            case "clearParameters"->{parameters.clear();yield null;}
            case "getConnection"->connection;
            case "executeUpdate"->{ensureOpen(statementClosed[0]);String text=prepared?sql:(String)args[0];yield update(text,arguments(parameters));}
            case "executeQuery"->{ensureOpen(statementClosed[0]);String text=prepared?sql:(String)args[0];yield query(text,arguments(parameters));}
            case "close"->{statementClosed[0]=true;yield null;}
            case "isClosed"->statementClosed[0];
            default->objectOrUnsupported(self,method,args);
        };
        return prepared?proxy(PreparedStatement.class,h):proxy(Statement.class,h);
    }
    private static List<Object> arguments(Map<Integer,Object> values)throws SQLException {
        List<Object> args=new ArrayList<>();for(int i=1;i<=values.size();i++){if(!values.containsKey(i))throw new SQLException("parameter gap");args.add(values.get(i));}return args;
    }
    private synchronized int update(String sql,List<Object> args)throws SQLException {
        send("U",sql,args);var reply=receive();if(reply[0].equals("U"))return Integer.parseInt(reply[1]);throw new SQLException("unexpected update reply");
    }
    private synchronized ResultSet query(String sql,List<Object> args)throws SQLException {
        send("Q",sql,args);String[] header=receive();if(!header[0].equals("R"))throw new SQLException("unexpected query header");
        List<String> columns=new ArrayList<>();for(int i=1;i<header.length;i++)columns.add((String)decode(header[i]));
        List<List<Object>> rows=new ArrayList<>();
        while(true){var line=receive();if(line[0].equals("END"))break;if(!line[0].equals("ROW"))throw new SQLException("unexpected row");var values=new ArrayList<Object>();for(int i=1;i<line.length;i++)values.add(decode(line[i]));rows.add(values);}
        int[] position={-1};boolean[] wasNull={false},isClosed={false};
        return proxy(ResultSet.class,(self,method,a)->switch(method.getName()) {
            case "next"->{ensureOpen(isClosed[0]);yield ++position[0]<rows.size();}
            case "wasNull"->wasNull[0];
            case "close"->{isClosed[0]=true;yield null;}
            case "isClosed"->isClosed[0];
            case "getString","getInt","getLong","getBytes","getBinaryStream","getObject","getBoolean"->{
                ensureOpen(isClosed[0]);if(position[0]<0||position[0]>=rows.size())throw new SQLException("cursor outside row");
                int index=a[0] instanceof Integer n?n-1:columns.indexOf((String)a[0]);if(index<0||index>=columns.size())throw new SQLException("unknown column "+a[0]);
                Object value=rows.get(position[0]).get(index);wasNull[0]=value==null;
                yield switch(method.getName()) {
                    case "getString"->value==null?null:value.toString();case "getInt"->value==null?0:Math.toIntExact(((Number)value).longValue());
                    case "getBoolean"->value!=null&&((Number)value).longValue()!=0;
                    case "getLong"->value==null?0L:((Number)value).longValue();case "getBinaryStream"->value==null?null:new ByteArrayInputStream((byte[])value);
                    default->value;
                };
            }
            default->objectOrUnsupported(self,method,a);
        });
    }
    private synchronized void command(String... fields)throws SQLException {
        write(String.join("\t",fields));if(!receive()[0].equals("OK"))throw new SQLException("unexpected control reply");
    }
    private void send(String op,String sql,List<Object> args)throws SQLException {
        var line=new StringBuilder(op).append('\t').append(encode(sql));for(Object arg:args)line.append('\t').append(encode(arg));write(line.toString());
    }
    private void write(String line)throws SQLException {if(closed)throw new SQLException("bridge closed");try{writer.write(line);writer.newLine();writer.flush();}catch(IOException e){throw new SQLException("bridge I/O",e);}}
    private String[] receive()throws SQLException {
        try {String line=reader.readLine();if(line==null)throw new SQLException("bridge process ended");var fields=line.split("\t",-1);
            if(fields[0].equals("E"))throw new SQLException((String)decode(fields[3]),fields[1],Integer.parseInt(fields[2]));return fields;
        }catch(IOException e){throw new SQLException("bridge I/O",e);}
    }
    private static String encode(Object v) {
        if(v==null)return "n";if(v instanceof byte[] b)return "b"+Base64.getEncoder().encodeToString(b);
        if(v instanceof Number)return "i"+v;if(v instanceof Boolean b)return b?"i1":"i0";
        return "s"+Base64.getEncoder().encodeToString(v.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static Object decode(String v)throws SQLException {
        if(v.equals("n"))return null;
        try{return switch(v.charAt(0)){case 'i'->Long.valueOf(v.substring(1));case 'b'->Base64.getDecoder().decode(v.substring(1));case 's'->new String(Base64.getDecoder().decode(v.substring(1)),StandardCharsets.UTF_8);default->throw new SQLException("unknown field type");};}
        catch(IllegalArgumentException e){throw new SQLException("bad bridge field",e);}
    }
    private static void ensureOpen(boolean closed)throws SQLException{if(closed)throw new SQLException("statement/result closed");}
    private static Object objectOrUnsupported(Object self,Method m,Object[] args)throws SQLException {
        return switch(m.getName()){case "toString"->"TEST_ONLY_SQLITE_BRIDGE:"+m.getDeclaringClass().getSimpleName();case "hashCode"->System.identityHashCode(self);case "equals"->self==args[0];case "isWrapperFor"->false;default->throw new SQLFeatureNotSupportedException("test bridge does not implement "+m);};
    }
    private static <T>T proxy(Class<T> type,InvocationHandler h){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},h));}
    @Override public synchronized void close()throws SQLException {
        if(closed)return;
        try {command("CLOSE");}
        finally {
            closed=true;
            try {writer.close();reader.close();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}
            catch(IOException e){process.destroyForcibly();throw new SQLException("closing bridge",e);}
            catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();throw new SQLException("interrupted closing bridge",e);}
        }
    }
}
