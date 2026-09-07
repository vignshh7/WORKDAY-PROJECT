import { Route, Routes } from 'react-router-dom';
import { AppLayout } from './layouts/AppLayout';
import { HomeRedirect, ProtectedRoute, RoleRoute } from './routes/guards';

import Login from './pages/auth/Login';
import Register from './pages/auth/Register';
import Notifications from './pages/Notifications';
import Availability from './pages/Availability';
import InterviewDetail from './pages/InterviewDetail';
import Integrations from './pages/settings/Integrations';
import { Forbidden, NotFound } from './pages/Errors';

import RecruiterOverview from './pages/recruiter/Overview';
import Candidates from './pages/recruiter/Candidates';
import CandidateDetail from './pages/recruiter/CandidateDetail';
import Jobs from './pages/recruiter/Jobs';
import JobDetail from './pages/recruiter/JobDetail';
import Interviews from './pages/recruiter/Interviews';
import Scheduling from './pages/recruiter/Scheduling';
import AiSchedulingPage from './pages/recruiter/AiScheduling';

import InterviewerDashboard from './pages/interviewer/Dashboard';
import CandidateDashboard from './pages/candidate/Dashboard';
import MyInterviews from './pages/candidate/MyInterviews';

import AdminOverview from './pages/admin/Overview';
import Users from './pages/admin/Users';
import AuditLogs from './pages/admin/AuditLogs';

const STAFF = ['RECRUITER', 'ADMIN'];

export default function App() {
  return (
    <Routes>
      {/* Public */}
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />

      {/* Everything below needs a valid token */}
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route index element={<HomeRedirect />} />

          {/* Any role */}
          <Route path="notifications" element={<Notifications />} />
          {/* This exact path is where the backend's OAuth callback returns the browser. */}
          <Route path="settings/integrations" element={<Integrations />} />
          <Route path="interviews/:roundId" element={<InterviewDetail />} />
          <Route path="forbidden" element={<Forbidden />} />

          {/* Recruiter + admin */}
          <Route element={<RoleRoute allow={STAFF} />}>
            <Route path="recruiter" element={<RecruiterOverview />} />
            <Route path="recruiter/candidates" element={<Candidates />} />
            <Route path="recruiter/jobs" element={<Jobs />} />
            <Route path="recruiter/interviews" element={<Interviews />} />
            <Route path="recruiter/scheduling" element={<Scheduling />} />
            <Route path="recruiter/ai" element={<AiSchedulingPage />} />
            <Route path="candidates/:id" element={<CandidateDetail />} />
          </Route>

          {/* Jobs are readable by any authenticated user */}
          <Route path="jobs/:id" element={<JobDetail />} />

          {/* Interviewer */}
          <Route element={<RoleRoute allow={['INTERVIEWER']} />}>
            <Route path="interviewer" element={<InterviewerDashboard />} />
            <Route path="interviewer/availability" element={<Availability />} />
          </Route>

          {/* Candidate */}
          <Route element={<RoleRoute allow={['CANDIDATE']} />}>
            <Route path="candidate" element={<CandidateDashboard />} />
            <Route path="candidate/interviews" element={<MyInterviews />} />
            <Route path="candidate/availability" element={<Availability />} />
          </Route>

          {/* Admin */}
          <Route element={<RoleRoute allow={['ADMIN']} />}>
            <Route path="admin" element={<AdminOverview />} />
            <Route path="admin/users" element={<Users />} />
            <Route path="admin/audit-logs" element={<AuditLogs />} />
          </Route>

          <Route path="*" element={<NotFound />} />
        </Route>
      </Route>
    </Routes>
  );
}
