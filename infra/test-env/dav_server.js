// Test WebDAV server. usage: node dav_server.js <port> <root>   (user nd / ndpass, Basic)
const webdav = require('webdav-server').v2;
const fs = require('fs');
const [port, root] = [parseInt(process.argv[2]), process.argv[3]];
fs.mkdirSync(root, { recursive: true });
const userManager = new webdav.SimpleUserManager();
const user = userManager.addUser('nd', 'ndpass', false);
const privilegeManager = new webdav.SimplePathPrivilegeManager();
privilegeManager.setRights(user, '/', ['all']);
const server = new webdav.WebDAVServer({
  port,
  httpAuthentication: new webdav.HTTPBasicAuthentication(userManager, 'nd'),
  privilegeManager,
  requireAuthentification: true,
});
server.setFileSystem('/dav', new webdav.PhysicalFileSystem(root), (ok) => {
  server.start(() => console.log('ready'));
});
