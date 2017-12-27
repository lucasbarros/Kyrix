// imports
const fs = require("fs");

/**
 *
 * @param {string} name - project name.
 * @param {string} dbFile - a configuration file for the database this project is associated with. This file contains a json blob specifying the host, user, and password of the database (see ./dbconfig.example for an example). NEVER check this file into the repo. 'dbconfig.txt' is added to gitignore, so it'll be convenient to name it as 'dbconfig.txt'. Note that if relative path is used, the path is relative to the directory the Kyrix spec is run in.
 * @constructor
 */
function Project(name, dbFile) {

    // name
    this.name = name;

    // db configs
    this.dbConfig = JSON.parse(fs.readFileSync(dbFile));

    // the set of canvases
    this.canvases = [];
}

// define prototype functions


// exports
module.exports = {
    Project : Project
}
