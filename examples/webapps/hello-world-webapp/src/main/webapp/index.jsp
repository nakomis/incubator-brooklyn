<html>
<% if (request.getServerName().contains("demo") || request.getServerName().contains("pilot")) {
%>

<head>
<title>AgriWise Holding Page</title>
<style type="text/css">
body {
  color: white;
  font-family: arial, helvetica, sans-serif;
}
</style>
</head>
<body bgcolor=#1b3721>

<table border="0">
<tr>
<td>
<img src="images/agriwise-logo.png">
</td>
</tr>
<tr>
<td>
<h1>This service is no longer available</h1>
</td>
</tr>
</table>

</body>

<% } else { %>
<head>
<title>Service Unavailable</title>
<style type="text/css">
body {
  color: white;
  font-family: arial, helvetica, sans-serif;
}
a {
  color: white !important;
}
</style>
</head>
<body bgcolor=#1b3721>

<table border="0">
<tr>
<td>
<img src="images/agriwise-logo.png">
</td>
</tr>
<tr>
<td>
<h1>This service is no longer available</h1>
</td>
</tr>
<tr>
<td>
Existing users should use the replacement service at: <a href="http://www.agrinowcloud.com">http://www.agrinowcloud.com</a>
<p>
Please update any bookmarks to reflect the new address
<p>
</td>
</tr>
</table>

</body>
<% } %>
</html>
