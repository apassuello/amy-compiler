object Variable {
  var i: Int =
    0;
  var j: Int =
    0;
  i =
    j;
  j =
    (i + 1);
  Std.printInt(i);
  Std.printInt(j)
}