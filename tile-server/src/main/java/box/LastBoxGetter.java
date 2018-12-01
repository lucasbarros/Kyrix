package box;

import org.locationtech.jts.io.ParseException;
import project.Canvas;
import project.Project;

import java.sql.SQLException;
import java.util.ArrayList;

public class LastBoxGetter extends BoxGetter {

    @Override
    public BoxandData getBox(Canvas c, int mx, int my, int viewportH, int viewportW, ArrayList<String> predicates, boolean hasBox)
            throws SQLException, ClassNotFoundException, ParseException {

        ArrayList<ArrayList<ArrayList<String>>> data;
        double wrapLength = 0.5;
        //scale is the modification ratio
        double scale = 0.4;
        int count = 0;
        int minx, miny, maxx, maxy;
        if (! hasBox) {
             minx = (int) Math.max(0, mx - wrapLength * viewportW);
             miny = (int) Math.max(0, my - wrapLength * viewportH);
             maxx = (int) Math.min(c.getH(), minx + (1 + 2 * wrapLength) * viewportW);
             maxy = (int) Math.min(c.getW(), miny + (1 + 2 * wrapLength) * viewportH);
            History.reset();
        } else {
            Box curBox = History.getBox();
            minx = curBox.getMinx();
            miny = curBox.getMiny();
            maxx = curBox.getMaxx();
            maxy = curBox.getMaxy();
        }

        data = fetchData(c, minx, miny, maxx, maxy, predicates, hasBox);

        for (int i = 0; i < data.get(0).size(); i++)
            count += data.get(i).size();

        double deltax = maxx - minx;
        double deltay = maxy - miny;
        if (count > 100000) {
            minx += deltax * scale / 2;
            maxx -= deltax * scale / 2;
            miny += deltay * scale / 2;
            maxy -= deltay * scale / 2;
        } else if (count < 1000) {
            minx -= deltax * scale / 2;
            maxx += deltax * scale / 2;
            miny -= deltay * scale / 2;
            maxy += deltay * scale / 2;
        }

        Box box = new Box(minx, miny, maxx, maxy);
        return new BoxandData(box, data);
    }
}
