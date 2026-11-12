      subroutine chmthg(n,m,p,w,c,jdimr,jdimc,z)

c*********************************************************************72
c
cc CHMTHG checks the input data for MTHG.
c
c  Modified:
c
c    06 December 2009
c
c  Author:
c
c    Silvano Mortello, Paolo Toth
c
c  Reference:
c
c    Silvano Martello, Paolo Toth,
c    Knapsack Problems: Algorithms and Computer Implementations,
c    Wiley, 1990,
c    ISBN: 0-471-92420-2,
c    LC: QA267.7.M37.
c
      integer p(jdimr,jdimc),w(jdimr,jdimc),c(jdimr),z

      if ( m .le. 1 ) z = - 1
      if ( m .gt. jdimr ) z = - 1
      if ( z .lt. 0 ) return
      if ( n .le. 1 ) z = - 2
      if ( n .gt. jdimc ) z = - 2
      if ( z .lt. 0 ) return
      do 40 i=1,m
        if ( c(i) .gt. 0 ) go to 10
        z = - 3
        return
   10   min = c(i) + 1
        do 30 j=1,n
          if ( p(i,j) .gt. 0 .and. w(i,j) .gt. 0 ) go to 20
          z = - 3
          return
   20     if ( w(i,j) .lt. min ) min = w(i,j)
   30   continue
        if ( c(i) .lt. min ) z = - 5
   40 continue
      do 60 j=1,n
        do 50 i=1,m
          if ( w(i,j) .le. c(i) ) go to 60
   50   continue
        z = - 4
        return
   60 continue
      return
      end
      subroutine feas(n,m,p,w,c,xstar,jfi,jdimr,jdimc)

c*********************************************************************72
c
cc FEAS checks for infeasibility.
c
c  Modified:
c
c    06 December 2009
c
c  Author:
c
c    Silvano Mortello, Paolo Toth
c
c  Reference:
c
c    Silvano Martello, Paolo Toth,
c    Knapsack Problems: Algorithms and Computer Implementations,
c    Wiley, 1990,
c    ISBN: 0-471-92420-2,
c    LC: QA267.7.M37.
c
      integer p(jdimr,jdimc),w(jdimr,jdimc),c(jdimr),xstar(jdimc)

      jfi = 0

      do j=1,n
        xstar(j) = 0
        kinf = 0
        do 10 i=1,m
          if ( w(i,j) .le. c(i) ) go to 10
          kinf = kinf + 1
          p(i,j) = 0
   10   continue
        if ( kinf .eq. m ) jfi = 1
      end do

      return
      end
      subroutine gha(p,w,c,n,m,z,xstar,iub,best,kvst,inf,
     &               jdimr,jdimc,kw,mw,pen,first,second,bb)

c*********************************************************************72
c
cc GHA applies the approximate algorithm gh with function (a).
c
c and
c define the infinite value  inf .
c
c if iub = z the solution is optimal;
c if z = kvst no feasible solution was found.
c
c  Modified:
c
c    06 December 2009
c
c  Author:
c
c    Silvano Mortello, Paolo Toth
c
c  Reference:
c
c    Silvano Martello, Paolo Toth,
c    Knapsack Problems: Algorithms and Computer Implementations,
c    Wiley, 1990,
c    ISBN: 0-471-92420-2,
c    LC: QA267.7.M37.
c
      integer p(jdimr,jdimc),w(jdimr,jdimc),c(jdimr),xstar(jdimc),
     &        best(jdimc),z
      integer kw(jdimr),mw(jdimr),pen(jdimc),first(jdimc),
     &        second(jdimc),bb(jdimc)
      integer fmax,smax
      inf = 0
      do i=1,m
        kw(i) = c(i)
        mw(i) = 0
        if ( c(i) .gt. inf ) inf = c(i)
      end do
      iub = 0
      z = 0
      kvst = 0
      do 40 j=1,n
        ipmin = p(1,j)
        fmax = p(1,j)
        if = 1
        smax = 0
        do 30 i=2,m
          if ( p(i,j) .lt. ipmin ) ipmin = p(i,j)
          if ( smax .ge. p(i,j) ) go to 30
          if ( fmax .ge. p(i,j) ) go to 20
          smax = fmax
          is = if
          fmax = p(i,j)
          if = i
          go to 30
   20     smax = p(i,j)
          is = i
   30   continue
        kvst = kvst + ipmin
        first(j) = if
        best(j) = if
        second(j) = is
        pen(j) = fmax - smax
        if ( smax .eq. 0 ) pen(j) = - 1
        bb(j) = j
        iub = iub + fmax
        if ( w(if,j) .gt. mw(if) ) mw(if) = w(if,j)
        if ( w(is,j) .gt. mw(is) ) mw(is) = w(is,j)
   40 continue
      if ( kvst .gt. 0 ) kvst = kvst - 1
      if ( iub .gt. inf ) inf = iub
      do 50 j=1,n
        if ( pen(j) .eq. (- 1) ) pen(j) = inf
   50 continue
      nb = n
   60 maxpen = - 1
      do 70 jj=1,nb
        j = bb(jj)
        if ( pen(j) .le. maxpen ) go to 70
        maxpen = pen(j)
        jjm = jj
   70 continue
      jo = bb(jjm)
      io = first(jo)
      z = z + p(io,jo)
      xstar(jo) = io
      bb(jjm) = bb(nb)
      nb = nb - 1
      if ( nb .eq. 0 ) return
      kw(io) = kw(io) - w(io,jo)
      if ( mw(io) .le. kw(io) ) go to 60
      do 120 jj=1,nb
        j = bb(jj)
        if ( w(io,j) .le. kw(io) ) go to 120
        if ( first(j) .ne. io ) go to 80
        if ( pen(j) .eq. inf ) go to 130
        first(j) = second(j)
        go to 90
   80   if ( second(j) .ne. io ) go to 120
   90   index = first(j)
        w(index,j) = w(index,j) + inf
        newsec = 0
        do 100 i=1,m
          if ( w(i,j) .gt. kw(i) ) go to 100
          if ( p(i,j) .le. newsec ) go to 100
          newsec = p(i,j)
          is = i
  100   continue
        w(index,j) = w(index,j) - inf
        if ( newsec .eq. 0 ) go to 110
        second(j) = is
        pen(j) = p(index,j) - newsec
        if ( w(is,j) .gt. mw(is) ) mw(is) = w(is,j)
        go to 120
  110   pen(j) = inf
  120 continue
      go to 60
  130 z = kvst
      return
      end
      subroutine ghbcd(p,w,c,n,m,z,xstar,inf,jdimr,jdimc,xsp,
     &           dmyr1,dmyr2,dmyr3,dmyr4,dmyr5,
     &           dmyc2,dmyc3,dmyc4,dmycr1,dmya)

c*********************************************************************72
c
cc GHBCD applies the approximate algorithm GH with functions (b), (c) and (d).
c
c  Modified:
c
c    06 December 2009
c
c  Author:
c
c    Silvano Mortello, Paolo Toth
c
c  Reference:
c
c    Silvano Martello, Paolo Toth,
c    Knapsack Problems: Algorithms and Computer Implementations,
c    Wiley, 1990,
c    ISBN: 0-471-92420-2,
c    LC: QA267.7.M37.
c
      integer p(jdimr,jdimc),w(jdimr,jdimc),c(jdimr),xstar(jdimc),z
      integer vsp,xsp(jdimc)
      integer dmyr1(jdimr),dmyr2(jdimr),dmyr3(jdimr),dmyr4(jdimr),
     &        dmyr5(jdimr),dmyc2(jdimc),dmyc3(jdimc),dmyc4(jdimc),
     &        dmya(jdimr,jdimc)
      real    dmycr1(jdimc)

      jj = 2
      a1 = 1.
      a2 = 0.
      a3 = 0.
      a4 = 0.
      a5 = 1.
   10 call ghx(p,w,c,n,m,vsp,xsp,a1,a2,a3,a4,a5,inf,jdimr,jdimc,
     &         dmyr1,dmyr2,dmyr3,dmyr4,dmyr5,dmyc2,dmyc3,dmyc4,
     &         dmycr1,dmya)
      if ( vsp .le. z ) go to 30
      z = vsp
      do 20 j=1,n
         xstar(j) = xsp(j)
   20 continue
   30 if ( jj .eq. 3 ) go to 40
      if ( jj .eq. 4 ) go to 50
      jj = 3
      a1 = 1.
      a2 = 0.
      a3 = 1.
      a4 = 0.
      a5 = 0.
      go to 10
   40 jj = 4
      a1 = 0.
      a2 = 1.
      a3 = 0.
      a4 = 1.
      a5 = 0.
      go to 10
   50 return
      end
      subroutine ghx(p,w,c,n,m,z,xstar,a1,a2,a3,a4,a5,inf,jdimr,jdimc,
     &               kw,mw,minw,kchan,kwr,first,second,bb,pen,wl)

c*********************************************************************72
c
cc GHX applies the approximate algorithm gh with function (b) or (c) or (d).
c
c  Modified:
c
c    06 December 2009
c
c  Author:
c
c    Silvano Mortello, Paolo Toth
c
c  Reference:
c
c    Silvano Martello, Paolo Toth,
c    Knapsack Problems: Algorithms and Computer Implementations,
c    Wiley, 1990,
c    ISBN: 0-471-92420-2,
c    LC: QA267.7.M37.
c
      integer p(jdimr,jdimc),w(jdimr,jdimc),xstar(jdimc),c(jdimr),z
      integer kw(jdimr),mw(jdimr),minw(jdimr),kchan(jdimr),kwr(jdimr),
     &        first(jdimc),second(jdimc),bb(jdimc),wl(jdimr,jdimc)
      real    pen(jdimc),maxpen

      do i=1,m
        kw(i) = c(i)
        mw(i) = 0
        minw(i) = inf
        do j=1,n
          wl(i,j) = w(i,j)
          if ( wl(i,j) .lt. minw(i) ) minw(i) = wl(i,j)
        end do
        kwr(i) = kw(i) - minw(i)
      end do

      z = 0
      do 60 j=1,n
        fmax = - inf
        if = 0
        smax = - inf
        do 40 i=1,m
          if ( wl(i,j) .gt. kw(i) ) go to 40
          if ( wl(i,j) .gt. kwr(i) ) wl(i,j) = kw(i)
          rwl = wl(i,j)
          rp = p(i,j)
          rkw = kw(i)
          s = (- a1*rwl + a2*rp)/(a3*rkw + a4*rwl + a5)
          if ( smax .ge. s ) go to 40
          if ( fmax .ge. s ) go to 30
          smax = fmax
          is = if
          fmax = s
          if = i
          go to 40
   30     smax = s
          is = i
   40   continue
        first(j) = if
        second(j) = is
        pen(j) = fmax - smax
        bb(j) = j
        if ( wl(if,j) .gt. mw(if) ) mw(if) = wl(if,j)
        if ( smax .gt. float(- inf) ) go to 50
        pen(j) = inf
        go to 60
   50   if ( wl(is,j) .gt. mw(is) ) mw(is) = wl(is,j)
   60 continue
      nb = n
   70 maxpen = - 1
      do 80 jj=1,nb
        j = bb(jj)
        if ( pen(j) .le. maxpen ) go to 80
        maxpen = pen(j)
        jjm = jj
   80 continue
      jo = bb(jjm)
      io = first(jo)
      z = z + p(io,jo)
      xstar(jo) = io
      bb(jjm) = bb(nb)
      nb = nb - 1
      kw(io) = kw(io) - w(io,jo)
      if ( nb .eq. 0 ) go to 210
      kk = 0
      do 110 i=1,m
        kchan(i) = 0
        if ( wl(i,jo) .gt. minw(i) ) go to 100
        minw(i) = inf
        do 90 jj=1,nb
          j = bb(jj)
          if ( wl(i,j) .lt. minw(i) ) minw(i) = wl(i,j)
   90   continue
        if ( minw(i) + mw(i) .le. kw(i) ) go to 100
        kk = 1
        kchan(i) = 1
  100   kwr(i) = kw(i) - minw(i)
  110 continue
      if ( mw(io) .le. kw(io) ) go to 120
      kk = 1
      kchan(io) = 1
  120 if ( kk .eq. 0 ) go to 70
      do 190 jj=1,nb
        j = bb(jj)
        jf = first(j)
        if ( pen(j) .lt. float(inf) ) go to 130
        if ( wl(jf,j) .gt. kw(jf) ) go to 200
        go to 190
  130   if ( kchan(jf) .eq. 0 ) go to 140
        if ( wl(jf,j) .gt. kwr(jf) ) go to 150
  140   js = second(j)
        if ( kchan(js) .eq. 0 ) go to 190
        if ( wl(js,j) .le. kwr(js) ) go to 190
  150   fmax = - inf
        smax = - inf
        if = 0
        do 170 i=1,m
          if ( wl(i,j) .gt. kw(i) ) go to 170
          if ( wl(i,j) .gt. kwr(i) ) wl(i,j) = kw(i)
          rwl = wl(i,j)
          rp = p(i,j)
          rkw = kw(i)
          s = (- a1*rwl + a2*rp)/(a3*rkw + a4*rwl + a5)
          if ( smax .ge. s ) go to 170
          if ( fmax .ge. s ) go to 160
          smax = fmax
          is = if
          fmax = s
          if = i
          go to 170
  160     smax = s
          is = i
  170   continue
        first(j) = if
        second(j) = is
        pen(j) = fmax - smax
        if ( wl(if,j) .gt. mw(if) ) mw(if) = wl(if,j)
        if ( smax .gt. float(- inf) ) go to 180
        pen(j) = inf
        go to 190
  180   if ( wl(is,j) .gt. mw(is) ) mw(is) = wl(is,j)
  190 continue
      go to 70
  200 z = 0
      return
c
c try to improve on the current solution z.
c
  210 do 230 j=1,n
        if = xstar(j)
        maxp = p(if,j)
        do 220 i=1,m
          if ( w(i,j) .gt. kw(i) ) go to 220
          if ( p(i,j) .le. maxp ) go to 220
          maxp = p(i,j)
          if = i
  220   continue
        ip = xstar(j)
        if ( if .eq. ip ) go to 230
        xstar(j) = if
        z = z + p(if,j) - p(ip,j)
        kw(ip) = kw(ip) + w(ip,j)
        kw(if) = kw(if) - w(if,j)
  230 continue

      return
      end
      subroutine mthg ( n, m, p, w, c, minmax, z, xstar, jck )

c*********************************************************************72
c
cc MTHG heuristically solves the generalized assignment problem.
c
c opt z = p(1,1)*x(1,1) + ... + p(1,n)*x(1,n) +
c                         ...                 +
c         p(m,1)*x(m,1) + ... + p(m,n)*x(m,n)
c
c     (where  opt = min  if  minmax = 1 ,  opt = max  if  minmax = 2 )
c
c subject to:
c
c       w(i,1)*x(i,1) + ... + w(i,n)*x(i,n) .le. c(i)  for i=1,...,m,
c       x(1,j) + ... + x(m,j) = 1                      for j=1,...,n,
c       x(i,j) = 0 or 1                     for i=1,...,m, j=1,...,n.
c
c the program is included in the volume
c   s. martello, p. toth, "knapsack problems: algorithms
c   and computer implementations", john wiley, 1990
c and implements the polynomial-time algorithms described
c in section  7.4 .
c
c the input problem must satisfy the conditions
c
c   1) 2 .le. m .le. jdimr ;
c   2) 2 .le. n .le. jdimc ;
c      ( jdimr  and  jdimc  are defined by the first two executable
c       statements;)
c   3) p(i,j), w(i,j) and c(i) positive integers;
c   4) w(i,j) .le. c(i) for at least one i, for j=1,...,n;
c   5) c(i) .ge. min (w(i,j)) for i=1,...,m.
c
c mthg calls 6 procedures: chmthg, feas, gha, ghbcd, ghx and trin.
c
c the program is completely self-contained and communication to it is
c achieved solely through the parameter list of mthg.
c no machine-dependent constant is used.
c the program is written in 1967 american national standard fortran
c and is accepted by the pfort verifier (pfort is the portable
c subset of ansi defined by the association for computing machinery).
c the program has been tested on a digital vax 11/780 and an h.p.
c 9000/840.
c
c mthg needs
c   6 arrays ( c ,  dmyr1 ,  dmyr2 ,  dmyr3 ,  dmyr4  and  dmyr5 ) of
c              length at least  jdimr ;
c   7 arrays ( xstar ,  best ,  dmyc1 ,  dmyc2 ,  dmyc3 ,  dmyc4  and
c              dmycr1 ) of length at least  jdimc ;
c   3 arrays ( p ,  w  and  a ) of length at least  jdimr x jdimc .
c
c the arrays are currently dimensioned to allow problems for which
c       m .le. 50 ,
c       n .le. 500
c (so, in the calling program, arrays  p  and  w  must be dimensioned
c at  (50,500) ). changing such limits necessitates changing the
c dimension of all the arrays in subroutine mthg, as well as the first
c two executable statements.
c
c  Modified:
c
c    06 December 2009
c
c  Author:
c
c    Silvano Mortello, Paolo Toth
c
c  Reference:
c
c    Silvano Martello, Paolo Toth,
c    Knapsack Problems: Algorithms and Computer Implementations,
c    Wiley, 1990,
c    ISBN: 0-471-92420-2,
c    LC: QA267.7.M37.
c
c meaning of the input parameters:
c n        = number of items;
c m        = number of knapsacks;
c p(i,j)   = profit of item j if assigned to knapsack i
c            (i=1,...,m; j=1,...,n);
c w(i,j)   = weight of item j if assigned to knapsack i
c            (i=1,...,m; j=1,...,n);
c c(i)     = capacity of knapsack i (i=1,...,m);
c minmax   = 1 if the objective function must be minimized,
c          = 2 if the objective function must be maximized;
c jck      = 1 if check on the input data is desired,
c          = 0 otherwise.
c
c meaning of the output parameters:
c z        = value of the solution found if z .gt. 0 ,
c          = 0 if no feasible solution is found,
c          = error in the input data (when jck=1) if z .lt. 0 : condi-
c            tion  - z  is violated;
c xstar(j) = knapsack where item j is inserted in the solution found.
c
c all the parameters are integer. on return of mthg all the input
c parameters are unchanged, but  p(i,j)  is set to  0  for all pairs
c (i,j)  such that  w(i,j) .gt. c(i) .
c
cf2py intent(in) n, m, p, w, c, minmax, jck
cf2py intent(out) z, xstar
      integer p(50,500),w(50,500),c(50),xstar(500),z
      integer zm
      integer best(500)
      integer a(50,500)
      integer dmyr1(50),dmyr2(50),dmyr3(50),dmyr4(50),dmyr5(50)
      integer dmyc1(500),dmyc2(500),dmyc3(500),dmyc4(500)
      real    dmycr1(500)
c
c  Definition of the internal parameters.
c
      jdimr = 50
      jdimc = 500
      z = 0
      if ( jck .eq. 1 ) call chmthg(n,m,p,w,c,jdimr,jdimc,z)
      if ( z .lt. 0 ) return
c
c  Initialize.
c
      invst = 0
      imult = - 1
      if ( minmax .eq. 2 ) go to 10
c
c  Transform the minimization problem into a maximization problem.
c
      call trin(p,n,m,invst,lam,jdimr,jdimc)
      imult = 1
c
c  Solve the maximization problem.
c
c  Check for infeasibility.
c
   10 call feas(n,m,p,w,c,xstar,jfi,jdimr,jdimc)
      if ( jfi .eq. 1 ) go to 30
c
c  First heuristic solution.
c
      call gha(p,w,c,n,m,z,xstar,iub,best,kvst,inf,
     &         jdimr,jdimc,dmyr1,dmyr2,dmyc1,dmyc2,dmyc3,dmyc4)
      if ( z .eq. iub ) go to 20
c
c  Second heuristic solution.
c
      call ghbcd(p,w,c,n,m,z,xstar,inf,
     &           jdimr,jdimc,dmyc1,dmyr1,dmyr2,dmyr3,dmyr4,dmyr5,
     &           dmyc2,dmyc3,dmyc4,dmycr1,a)
c
c  Terminate.
c
   20 zm = z
      z = 0
      if ( zm .gt. kvst ) z = invst - zm*imult
   30 if ( minmax .eq. 2 ) return
c
c  Restore the original minimization problem.
c
      do i=1,m
        do j=1,n
          if ( p(i,j) .gt. 0 ) p(i,j) = lam - p(i,j)
        end do
      end do

      return
      end
      subroutine trin(p,n,m,invst,lam,jdimr,jdimc)

c*********************************************************************72
c
cc TRIN transforms an gap in minimization form to an equivalent instance in maximization form.
c
c  Modified:
c
c    06 December 2009
c
c  Author:
c
c    Silvano Mortello, Paolo Toth
c
c  Reference:
c
c    Silvano Martello, Paolo Toth,
c    Knapsack Problems: Algorithms and Computer Implementations,
c    Wiley, 1990,
c    ISBN: 0-471-92420-2,
c    LC: QA267.7.M37.
c
      integer p(jdimr,jdimc)
      invst = 0
      max = 0
      do 20 j=1,n
        do i=1,m
          if ( p(i,j) .gt. max ) max = p(i,j)
        end do
   20 continue
      lam = max + 1
      do 40 j=1,n
        do 30 i=1,m
          p(i,j) = lam - p(i,j)
   30   continue
        invst = invst + lam
   40 continue
      return
      end
