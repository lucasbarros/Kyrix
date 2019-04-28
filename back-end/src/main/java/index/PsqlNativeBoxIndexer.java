package index;

import jdk.nashorn.api.scripting.NashornScriptEngine;
import main.Config;
import main.DbConnector;
import main.Main;
import project.Canvas;
import project.Layer;
import project.Transform;
import box.Box;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import java.io.StringReader;
import java.util.function.*;

/**
 * Created by wenbo on 12/30/18.
 */
public class PsqlNativeBoxIndexer extends Indexer {

    private static PsqlNativeBoxIndexer instance = null;
    private static boolean isCitus = false;
    private static boolean useCopyFrom = false;

    // singleton pattern to ensure only one instance existed
    private PsqlNativeBoxIndexer(boolean isCitus) { this.isCitus = isCitus; }

    // thread-safe instance getter
    public static synchronized PsqlNativeBoxIndexer getInstance(boolean isCitus) {

        if (instance == null)
            instance = new PsqlNativeBoxIndexer(isCitus);
        return instance;
    }

    void run_citus_dml_ddl(Statement stmt, String sql) throws Exception {
        System.out.println(sql);
        stmt.executeUpdate(sql);
        sql = "select run_command_on_workers($CITUS$ "+sql+" $CITUS$);";
        System.out.println(sql);
        stmt.executeQuery(sql);
    }
    
    void run_citus_dml_ddl2(Statement stmt, String masterSql, String workerSql) throws Exception {
        System.out.println(masterSql);
        stmt.executeUpdate(masterSql);
        String dworkerSql = "select run_command_on_workers($CITUS$ "+workerSql+" $CITUS$);";
        System.out.println(dworkerSql);
        stmt.executeQuery(dworkerSql);
    }

    @Override
    public void createMV(Canvas c, int layerId) throws Exception {

        Connection dbConn = DbConnector.getDbConn(Config.dbServer, Config.databaseName, Config.userName, Config.password);
        if (! (dbConn instanceof PGConnection)) {
            throw new Exception("PsqlNativeBoxIndexer.createMV(): expected DbConnector.getDbConn() to return a PGConnection.");
        }
        Layer l = c.getLayers().get(layerId);
        Transform trans = l.getTransform();

        // step 0: create tables for storing bboxes and tiles
        String bboxTableName = "bbox_" + Main.getProject().getName() + "_" + c.getId() + "layer" + layerId;

        // drop table if exists
        Statement dropCreateStmt = DbConnector.getStmtByDbName(Config.databaseName);
        String sql = "drop table if exists " + bboxTableName + ";";
        System.out.println(sql);
        dropCreateStmt.executeUpdate(sql);

        // create the bbox table
        // yes, citus supports unlogged tables!
        // http://docs.citusdata.com/en/v8.1/performance/performance_tuning.html#postgresql-tuning
        sql = "CREATE UNLOGGED TABLE " + bboxTableName + " (";
        for (int i = 0; i < trans.getColumnNames().size(); i ++)
            sql += trans.getColumnNames().get(i) + " text, ";
        if (isCitus) {
            sql += "citus_distribution_id int, ";
        }
        sql += "cx double precision, cy double precision, minx double precision, miny double precision, maxx double precision, maxy double precision, geom box)";
        System.out.println(sql);
        dropCreateStmt.executeUpdate(sql);
        DbConnector.commitConnection(Config.databaseName);
        dropCreateStmt.close();
        
        // if this is an empty layer, return
        if (trans.getDb().equals(""))
            return ;

        // if this is an empty layer, return
        if (trans.getDb().equals("src_db_same_as_kyrix")) {

            // TODO: replace me.
            System.out.println("Transform database marked 'src_db_same_as_kyrix' - trying to pushdown...");
            Statement pushdownIndexStmt = DbConnector.getStmtByDbName(Config.databaseName);
            
            String transformResultType = bboxTableName+"_transform_response_type";
            String transformFuncName = bboxTableName+"_transform_func";
            String bboxFuncName = bboxTableName+"_bbox_func";
            java.util.function.UnaryOperator<String> tsql = (sqlstr) -> {
                return (sqlstr.replaceAll("transtype", transformResultType).replaceAll("transfunc", transformFuncName).
                        replaceAll("bboxfunc", bboxFuncName).replaceAll("bboxtbl", bboxTableName).
                        replaceAll("dbsource", trans.getDbsource()) );
            };
            
            // register transform JS function with Postgres/Citus
            run_citus_dml_ddl(pushdownIndexStmt, tsql.apply("DROP TYPE IF EXISTS transtype CASCADE"));
            run_citus_dml_ddl(pushdownIndexStmt, tsql.apply("CREATE TYPE transtype as (id bigint,x int,y int)"));
            run_citus_dml_ddl(pushdownIndexStmt, tsql.apply("DROP FUNCTION IF EXISTS transfunc"));

            String transFuncStr = trans.getTransformFunc();
            // TODO: analyze trans.getTransformFunc() to find arguments - for now, hardcode

            // TODO: generate bbox func - see Indexer.getBboxCoordinates()

            sql = tsql.apply("CREATE OR REPLACE FUNCTION bboxfunc(id bigint,x int,y int) returns kyrix_bbox_coords_type"+
                             " AS $$ return { cx: x, cy: y, minx: x-0.5, miny: y-0.5, maxx: x+0.5, maxy: y+0.5, }"+
                             "$$ LANGUAGE plv8");
            // master needs 'stable' for citus to pushdown to workers
            // workers need 'volatile' for pg11 to memoize and not call the function repeatedly per row
            run_citus_dml_ddl2(pushdownIndexStmt, sql + " STABLE", sql); // append is safer than replace...

            sql = tsql.apply("CREATE OR REPLACE FUNCTION transfunc(id bigint,w int,h int) returns transtype"+
                             // TODO(security): SQL injection - perhaps use $foo<hard to guess number>$ ... $foo<#>$ ?
                             " AS $$ "+ trans.getTransformFuncBody() +" $$ LANGUAGE plv8");
            // master needs 'stable' for citus to pushdown to workers
            // workers need 'volatile' for pg11 to memoize and not call the function repeatedly per row
            run_citus_dml_ddl2(pushdownIndexStmt, sql + " STABLE", sql); // append is safer than replace...

            // by distributing afterwards, loading is ~10x faster (minus a few minutes to distribute the data)
            sql = tsql.apply("SELECT create_distributed_table('bboxtbl', 'citus_distribution_id',"+
                             "colocate_with => 'dbsource')");
            System.out.println(sql);
            pushdownIndexStmt.executeQuery(sql);
            
            for (int i = 0; i < 100; i++) {
                // break into 100 steps so we can hopefully see progress
                System.out.println("pipeline/insertion stage "+i+" of 100...");
                sql = tsql.apply(  //"BEGIN; SET citus.task_executor_type = 'task-tracker';" +
                           "INSERT INTO bboxtbl (id,x,y,citus_distribution_id, "+
                           "   cx, cy, minx, miny, maxx, maxy) "+
                           "SELECT id, x, y, citus_distribution_id, "+
                           "(coords::kyrix_bbox_coords_type).cx, (coords::kyrix_bbox_coords_type).cy,"+
                           "(coords::kyrix_bbox_coords_type).minx, (coords::kyrix_bbox_coords_type).miny,"+
                           "(coords::kyrix_bbox_coords_type).maxx, (coords::kyrix_bbox_coords_type).maxy "+
                           "FROM ("+
                           "  SELECT (v::transtype).id, (v::transtype).x, (v::transtype).y, "+
                           "         citus_distribution_id, "+
                           "         bboxfunc( (v::transtype).id, (v::transtype).x, (v::transtype).y ) coords"+
                           "  FROM ("+
                           "    SELECT transfunc(id,w,h) v, citus_distribution_id FROM dbsource "+
                           "    WHERE w % 100 = "+i+
                           "  ) sq1"+
                           ") sq2");
                if (i % 10 == 0) {
                    long startts = System.nanoTime();
                    System.out.println(sql);
                    pushdownIndexStmt.executeUpdate(sql);
                    long elapsed = System.nanoTime() - startts;
                    System.out.println("stage "+i+" took "+(elapsed/1000000)+" msec");
                }
            }

            // compute geom field in the database, where it can happen in parallel
            Statement setGeomFieldStmt = DbConnector.getStmtByDbName(Config.databaseName);
            sql = "UPDATE "+bboxTableName+" SET geom=box( point(minx,miny), point(maxx,maxy) );";
            System.out.println(sql);
            long startTs = (new Date()).getTime();
            setGeomFieldStmt.executeUpdate(sql);
            DbConnector.commitConnection(Config.databaseName);
            setGeomFieldStmt.close();
            long currTs = (new Date()).getTime();
            System.out.println( ((currTs-startTs)/1000) + " secs for setting geom field" + (isCitus?" in parallel":""));
            startTs = currTs;
                
            // create index - gist/spgist require logged table type
            // TODO: consider sp-gist
            Statement createIndexStmt = DbConnector.getStmtByDbName(Config.databaseName);
            sql = "CREATE INDEX sp_" + bboxTableName + " ON " + bboxTableName + " USING gist (geom);";
            System.out.println(sql);
            createIndexStmt.executeUpdate(sql);
            DbConnector.commitConnection(Config.databaseName);
            createIndexStmt.close();
            currTs = (new Date()).getTime();
            System.out.println( ((currTs-startTs)/1000) + " secs for CREATE INDEX sp_"+bboxTableName+" ON "+bboxTableName + (isCitus?" in parallel":""));
            startTs = currTs;

            return ;
        }

        // step 1: set up nashorn environment for running javascript code
        NashornScriptEngine engine = null;
        if (! trans.getTransformFunc().equals(""))
            engine = setupNashorn(trans.getTransformFunc());

        // step 2: looping through query results
        // TODO: distinguish between separable and non-separable cases
        String transDb = trans.getDb();
        String transQuery = trans.getQuery();
        System.out.println("db="+transDb+" - query="+transQuery);
        Statement rawDBStmt = DbConnector.getStmtByDbName(transDb);
        ResultSet rs = DbConnector.getQueryResultIterator(rawDBStmt, transQuery);
        int numColumn = rs.getMetaData().getColumnCount();
        int rowCount = 0;
        String copySql = "COPY " + bboxTableName + "(";
        String insertSql = "INSERT INTO " + bboxTableName + " VALUES (";
        // for debugging, vary number of spaces after the commas
        for (int i = 0; i < trans.getColumnNames().size(); i ++) {
            insertSql += "?,";
            copySql += trans.getColumnNames().get(i) + ",";
        }
        if (isCitus) insertSql += "?,";
        insertSql += "?,?,?,?,?,?)";
        copySql += "citus_distribution_id,cx,cy,minx,miny,maxx,maxy) FROM STDIN;\n";
        System.out.println(useCopyFrom ? copySql : insertSql);
        PreparedStatement preparedStmt = dbConn.prepareStatement(insertSql);
        Statement copyFromStmt = DbConnector.getStmtByDbName(Config.databaseName);
        long startTs = (new Date()).getTime();
        long lastTs = startTs;
        long currTs = 0;
        long secs = 0;
        int numcols = 0;
        // much larger batch sizes for COPY FROM
        int batchsize = useCopyFrom ? Config.bboxBatchSize*2 : Config.bboxBatchSize;
        boolean isNullTransform = trans.getTransformFunc().equals("");
        System.out.println("batchsize="+String.valueOf(batchsize)+"  numColumn=" + String.valueOf(numColumn));
        StringBuffer copybuffer = new StringBuffer(batchsize * 40);
        while (rs.next()) {

            // count log - important to increment early so modulo-zero doesn't trigger on first iteration
            rowCount++;

            // get raw row
            ArrayList<String> curRawRow = new ArrayList<>();
            for (int i = 1; i <= numColumn; i ++)
                curRawRow.add(rs.getString(i));

            // step 3: run transform function on this tuple
            ArrayList<String> transformedRow = isNullTransform ? curRawRow : getTransformedRow(c, curRawRow, engine);

            // step 4: calculate bounding boxes
            ArrayList<Double> curBbox = getBboxCoordinates(l, transformedRow);

            // insert into bbox table
            if (numcols == 0) {
                numcols = trans.getColumnNames().size();
                System.out.println("numcols=" + String.valueOf(numcols));
            }
            if (useCopyFrom) {
                // TODO: for performance, disallow tabs and newlines during input
                for (int i = 0; i < numcols; i++) {
                    copybuffer.append(transformedRow.get(i).replaceAll("\t", "\\t").replaceAll("\n", "\\n"));
                    copybuffer.append("\t");
                }
                // String.valueOf is fast + portable: https://stackoverflow.com/a/654049
                if (isCitus) {
                    copybuffer.append(String.valueOf(rowCount));
                    copybuffer.append("\t");
                }
                copybuffer.append(String.valueOf(curBbox.get(0)));
                for (int i = 1; i < 6; i ++) {
                    copybuffer.append("\t");
                    copybuffer.append(String.valueOf(String.valueOf(curBbox.get(i))));
                }
                copybuffer.append("\n");
                if (rowCount == 10) {
                    System.out.println(copySql + copybuffer);
                }
                if (rowCount % batchsize == 0) {
                    long rows = ((PGConnection) dbConn).getCopyAPI().copyIn(copySql, new StringReader(copybuffer.toString()));
                    DbConnector.commitConnection(Config.databaseName);
                    //System.out.println("successfully bulk loaded "+String.valueOf(rows)+" rows.");
                    copybuffer.setLength(0);
                }
            } else {
                int pscol = 1;
                for (int i = 0; i < numcols; i++)
                    preparedStmt.setString(pscol++, transformedRow.get(i).replaceAll("\'", "\'\'"));
                if (isCitus)
                    preparedStmt.setDouble(pscol++, rowCount);
                for (int i = 0; i < 6; i ++) preparedStmt.setDouble(pscol++, curBbox.get(i));
                preparedStmt.addBatch();
                if (rowCount % batchsize == 0) {
                    preparedStmt.executeBatch();
                    DbConnector.commitConnection(Config.databaseName);
                }
            }

            if (rowCount % 1000 == 0) {   // perf: only measure to the nearest 1K recs/sec (getTime() is expensive)
                currTs = (new Date()).getTime();
                if (currTs/10000 > lastTs/10000) { // print every N=10 seconds
                    lastTs = currTs;
                    secs = (currTs-startTs)/1000;
                    if (secs > 0) {
                        System.out.println(secs + " secs: "+rowCount+" records inserted. "+(rowCount/secs)+" recs/sec.");
                    }
                }
            }
        }

        // insert tail stuff
        if (rowCount % batchsize != 0) {
            if (useCopyFrom) {                                                                            
                long rows = ((PGConnection) dbConn).getCopyAPI().copyIn(copySql, new StringReader(copybuffer.toString()));
                DbConnector.commitConnection(Config.databaseName);
            } else {
                preparedStmt.executeBatch();
            }
            DbConnector.commitConnection(Config.databaseName);
        }
        if (useCopyFrom) {                                                                            
            copyFromStmt.close();
        } else {
            preparedStmt.close();
        }

        // close reader connection
        rs.close();
        rawDBStmt.close();
        DbConnector.closeConnection(trans.getDb());

        startTs = (new Date()).getTime();
        
        // TODO: move to parallel kyrix-indexing: pushdown this computation into the DB and run on each shard independently.
        if (isCitus) {
            Statement distributeStmt = DbConnector.getStmtByDbName(Config.databaseName);

            // by distributing afterwards, loading is ~10x faster (minus a few minutes to distribute the data)
            sql = "SELECT create_distributed_table('"+bboxTableName+"', 'citus_distribution_id');";
            System.out.println(sql);
            distributeStmt.executeQuery(sql);
            DbConnector.commitConnection(Config.databaseName);

            // citus leaves leftover data on master when distributing non-empty tables - who knows why?
            sql = "BEGIN; SET LOCAL citus.enable_ddl_propagation TO off; TRUNCATE "+bboxTableName+"; END;";
            System.out.println(sql);
            distributeStmt.executeUpdate(sql);
            DbConnector.commitConnection(Config.databaseName);
            distributeStmt.close();

            currTs = (new Date()).getTime();
            System.out.println( ((currTs-startTs)/1000) + " secs for create_distributed_table()");
            startTs = currTs;
        }

        // compute geom field in the database, where it can happen in parallel
        Statement setGeomFieldStmt = DbConnector.getStmtByDbName(Config.databaseName);
        sql = "UPDATE "+bboxTableName+" SET geom=box( point(minx,miny), point(maxx,maxy) );";
        System.out.println(sql);
        setGeomFieldStmt.executeUpdate(sql);
        DbConnector.commitConnection(Config.databaseName);
        setGeomFieldStmt.close();

        currTs = (new Date()).getTime();
        System.out.println( ((currTs-startTs)/1000) + " secs for setting geom field" + (isCitus?" in parallel":""));
        startTs = currTs;
                
        // create index - gist/spgist require logged table type
        // TODO: consider sp-gist
        Statement createIndexStmt = DbConnector.getStmtByDbName(Config.databaseName);
        sql = "CREATE INDEX sp_" + bboxTableName + " ON " + bboxTableName + " USING gist (geom);";
        System.out.println(sql);
        createIndexStmt.executeUpdate(sql);
        DbConnector.commitConnection(Config.databaseName);
        createIndexStmt.close();

        currTs = (new Date()).getTime();
        System.out.println( ((currTs-startTs)/1000) + " secs for CREATE INDEX sp_"+bboxTableName+" ON "+bboxTableName + (isCitus?" in parallel":""));
        startTs = currTs;

        // don't use clustering
        //sql = "cluster " + bboxTableName + " using sp_" + bboxTableName + ";";
        //System.out.println(sql);
        //bboxStmt.executeUpdate(sql);
        //DbConnector.commitConnection(Config.databaseName);
    }

    @Override
    public ArrayList<ArrayList<String>> getDataFromRegion(Canvas c, int layerId, String regionWKT, String predicate, Box newBox, Box oldBox)
            throws Exception {

        // get column list string
        String colListStr = c.getLayers().get(layerId).getTransform().getColStr("");

        // construct range query
        String sql = "select " + colListStr + " from bbox_" + Main.getProject().getName() + "_"
            + c.getId() + "layer" + layerId + " where ";
        sql += "geom && box('"+newBox.getCSV()+"')";
        sql += "and not (geom && box('"+oldBox.getCSV()+"') )";
        if (predicate.length() > 0)
            sql += " and " + predicate + ";";
        System.out.println(sql);

        // return
        return DbConnector.getQueryResult(Config.databaseName, sql);
    }

    @Override
    public ArrayList<ArrayList<String>> getDataFromTile(Canvas c, int layerId, int minx, int miny, String predicate)
            throws Exception {

        // get column list string
        String colListStr = c.getLayers().get(layerId).getTransform().getColStr("");

        // construct range query
        String sql = "select " + colListStr + " from bbox_" + Main.getProject().getName() + "_"
            + c.getId() + "layer" + layerId + " where ";
        String boxStr = "geom && box( '"+minx + "," + miny + "," + (minx + Config.tileW) + "," + (miny+Config.tileH) + "')";
        sql += boxStr;
        if (predicate.length() > 0)
            sql += " and " + predicate;
        sql += ";";
        System.out.println(boxStr +" : " + sql);

        // return
        return DbConnector.getQueryResult(Config.databaseName, sql);
    }
}